/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.java;

import static com.facebook.buck.zip.ZipOutputStreams.HandleDuplicates.APPEND_TO_ZIP;

import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.RichStream;
import com.facebook.buck.zip.CustomJarOutputStream;
import com.facebook.buck.zip.CustomZipEntry;
import com.facebook.buck.zip.DeterministicManifest;
import com.facebook.buck.zip.ZipOutputStreams;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.annotation.Nullable;

public class JarBuilder {
  public interface Observer {
    Observer IGNORING =
        new Observer() {
          @Override
          public void onDuplicateEntry(String jarFile, JarEntrySupplier entrySupplier)
              throws IOException {}

          @Override
          public void onEntryOmitted(String jarFile, JarEntrySupplier entrySupplier)
              throws IOException {}
        };

    void onDuplicateEntry(String jarFile, JarEntrySupplier entrySupplier) throws IOException;

    void onEntryOmitted(String jarFile, JarEntrySupplier entrySupplier) throws IOException;
  }

  private Observer observer = Observer.IGNORING;
  @Nullable private Path outputFile;
  @Nullable private CustomJarOutputStream jar;
  @Nullable private String mainClass;
  @Nullable private Path manifestFile;
  private boolean shouldMergeManifests;
  private boolean shouldHashEntries;
  private Iterable<Pattern> blacklist = new ArrayList<>();
  private List<JarEntryContainer> sourceContainers = new ArrayList<>();
  private Set<String> alreadyAddedEntries = new HashSet<>();

  public JarBuilder setObserver(Observer observer) {
    this.observer = observer;
    return this;
  }

  public JarBuilder setEntriesToJar(Stream<Path> entriesToJar) {
    return setEntriesToJar(entriesToJar::iterator);
  }

  public JarBuilder setEntriesToJar(Iterable<Path> entriesToJar) {
    sourceContainers.clear();

    RichStream.from(entriesToJar)
        .peek(path -> Preconditions.checkArgument(path.isAbsolute()))
        .map(JarEntryContainer::of)
        .forEach(sourceContainers::add);

    return this;
  }

  public JarBuilder addEntryContainer(JarEntryContainer container) {
    sourceContainers.add(container);
    return this;
  }

  public JarBuilder setAlreadyAddedEntries(ImmutableSet<String> alreadyAddedEntries) {
    alreadyAddedEntries.forEach(this.alreadyAddedEntries::add);
    return this;
  }

  public JarBuilder setMainClass(String mainClass) {
    this.mainClass = mainClass;
    return this;
  }

  public JarBuilder setManifestFile(@Nullable Path manifestFile) {
    Preconditions.checkArgument(manifestFile == null || manifestFile.isAbsolute());
    this.manifestFile = manifestFile;
    return this;
  }

  public JarBuilder setShouldMergeManifests(boolean shouldMergeManifests) {
    this.shouldMergeManifests = shouldMergeManifests;
    return this;
  }

  public JarBuilder setShouldHashEntries(boolean shouldHashEntries) {
    this.shouldHashEntries = shouldHashEntries;
    return this;
  }

  public JarBuilder setEntryPatternBlacklist(Iterable<Pattern> blacklist) {
    this.blacklist = blacklist;
    return this;
  }

  public int createJarFile(Path outputFile) throws IOException {
    Preconditions.checkArgument(outputFile.isAbsolute());
    try (CustomJarOutputStream jar =
        ZipOutputStreams.newJarOutputStream(outputFile, APPEND_TO_ZIP)) {
      jar.setEntryHashingEnabled(shouldHashEntries);
      return appendToJarFile(outputFile, jar);
    }
  }

  public int appendToJarFile(Path outputFile, CustomJarOutputStream jar) throws IOException {
    Preconditions.checkArgument(outputFile.isAbsolute());
    this.outputFile = outputFile;
    this.jar = jar;

    // Write the manifest first.
    writeManifest();

    for (JarEntryContainer sourceContainer : sourceContainers) {
      addEntriesToJar(sourceContainer);
    }

    if (mainClass != null && !mainClassPresent()) {
      throw new HumanReadableException("ERROR: Main class %s does not exist.", mainClass);
    }

    return 0;
  }

  private void writeManifest() throws IOException {
    DeterministicManifest manifest = jar.getManifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

    if (shouldMergeManifests) {
      for (JarEntryContainer sourceContainer : sourceContainers) {
        Manifest readManifest = sourceContainer.getManifest();
        if (readManifest != null) {
          merge(manifest, readManifest);
        }
      }
    }

    // Even if not merging manifests, we should include the one the user gave us. We do this last
    // so that values from the user overwrite values from merged manifests.
    if (manifestFile != null) {
      try (InputStream stream = Files.newInputStream(manifestFile)) {
        Manifest readManifest = new Manifest(stream);
        merge(manifest, readManifest);
      }
    }

    // We may have merged manifests and over-written the user-supplied main class. Add it back.
    if (mainClass != null) {
      manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
    }

    jar.writeManifest();
  }

  private boolean mainClassPresent() {
    String mainClassPath = classNameToPath(mainClass);

    return alreadyAddedEntries.contains(mainClassPath);
  }

  private String classNameToPath(String className) {
    return className.replace('.', '/') + ".class";
  }

  public static String pathToClassName(String relativePath) {
    String entry = relativePath;
    if (relativePath.contains(".class")) {
      entry = relativePath.replace('/', '.').replace(".class", "");
    }
    return entry;
  }

  private void addEntriesToJar(JarEntryContainer container) throws IOException {
    Iterable<JarEntrySupplier> entries = container.stream()::iterator;
    for (JarEntrySupplier entrySupplier : entries) {
      addEntryToJar(entrySupplier);
    }
  }

  private void addEntryToJar(JarEntrySupplier entrySupplier) throws IOException {
    CustomZipEntry entry = entrySupplier.getEntry();
    String entryName = entry.getName();

    // We already read the manifest. No need to read it again
    if (JarFile.MANIFEST_NAME.equals(entryName)) {
      return;
    }

    // Check if the entry belongs to the blacklist and it should be excluded from the Jar.
    if (shouldEntryBeRemovedFromJar(entrySupplier)) {
      return;
    }

    // We're in the process of merging a bunch of different jar files. These typically contain
    // just ".class" files and the manifest, but they can also include things like license files
    // from third party libraries and config files. We should include those license files within
    // the jar we're creating. Extracting them is left as an exercise for the consumer of the
    // jar.  Because we don't know which files are important, the only ones we skip are
    // duplicate class files.
    if (!isDuplicateAllowed(entryName) && !alreadyAddedEntries.add(entryName)) {
      if (!entryName.endsWith("/")) {
        observer.onDuplicateEntry(String.valueOf(outputFile), entrySupplier);
      }
      return;
    }

    jar.putNextEntry(entry);
    try (InputStream entryInputStream = entrySupplier.getInputStreamSupplier().get()) {
      if (entryInputStream != null) {
        // Null stream means a directory
        ByteStreams.copy(entryInputStream, jar);
      }
    }
    jar.closeEntry();
  }

  private boolean shouldEntryBeRemovedFromJar(JarEntrySupplier supplier) throws IOException {
    CustomZipEntry entry = supplier.getEntry();
    String entryKey = pathToClassName(entry.getName());
    for (Pattern pattern : blacklist) {
      if (pattern.matcher(entryKey).find()) {
        observer.onEntryOmitted(String.valueOf(outputFile), supplier);
        return true;
      }
    }
    return false;
  }

  /**
   * Merge entries from two Manifests together, with existing attributes being overwritten.
   *
   * @param into The Manifest to modify.
   * @param from The Manifest to copy from.
   */
  private void merge(Manifest into, Manifest from) {

    Attributes attributes = from.getMainAttributes();
    if (attributes != null) {
      for (Map.Entry<Object, Object> attribute : attributes.entrySet()) {
        into.getMainAttributes().put(attribute.getKey(), attribute.getValue());
      }
    }

    Map<String, Attributes> entries = from.getEntries();
    if (entries != null) {
      for (Map.Entry<String, Attributes> entry : entries.entrySet()) {
        Attributes existing = into.getAttributes(entry.getKey());
        if (existing == null) {
          existing = new Attributes();
          into.getEntries().put(entry.getKey(), existing);
        }
        existing.putAll(entry.getValue());
      }
    }
  }

  private boolean isDuplicateAllowed(String name) {
    return !name.endsWith(".class") && !name.endsWith("/");
  }
}
