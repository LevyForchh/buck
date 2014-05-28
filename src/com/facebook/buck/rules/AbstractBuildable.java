/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.HasBuildTarget;
import com.google.common.base.Preconditions;

import java.nio.file.Path;

import javax.annotation.Nullable;

/**
 * No-op implementations of all methods from {@link Buildable}.
 */
public abstract class AbstractBuildable implements Buildable, HasBuildTarget {

  protected final BuildTarget target;

  protected AbstractBuildable(BuildTarget target) {
    this.target = Preconditions.checkNotNull(target);
  }

  @Override
  public BuildableProperties getProperties() {
    return BuildableProperties.NONE;
  }

  @Nullable
  @Override
  public abstract Path getPathToOutputFile();

  @Override
  public BuildTarget getBuildTarget() {
    return target;
  }

  public static class AnonymousBuildRule extends AbstractBuildRule implements BuildRule {
    private final Buildable buildable;
    private final BuildRuleType type;

    public AnonymousBuildRule(BuildRuleType type, Buildable buildable, BuildRuleParams params) {
      super(params, buildable);
      this.buildable = buildable;
      this.type = type;
    }

    @Override
    public BuildableProperties getProperties() {
      return buildable.getProperties();
    }

    @Override
    public Buildable getBuildable() {
      return buildable;
    }

    @Override
    public BuildRuleType getType() {
      return type;
    }
  }
}
