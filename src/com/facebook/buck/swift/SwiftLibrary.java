/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.swift;

import static com.facebook.buck.swift.SwiftLibraryDescription.SWIFT_COMPANION_FLAVOR;
import static com.facebook.buck.swift.SwiftLibraryDescription.SWIFT_COMPILE_FLAVOR;

import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.cxx.CxxHeadersDir;
import com.facebook.buck.cxx.CxxLink;
import com.facebook.buck.cxx.CxxPreprocessables;
import com.facebook.buck.cxx.CxxPreprocessorDep;
import com.facebook.buck.cxx.CxxPreprocessorInput;
import com.facebook.buck.cxx.LinkerMapMode;
import com.facebook.buck.cxx.platform.CxxPlatform;
import com.facebook.buck.cxx.platform.Linker;
import com.facebook.buck.cxx.platform.NativeLinkable;
import com.facebook.buck.cxx.platform.NativeLinkableInput;
import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Flavor;
import com.facebook.buck.model.FlavorDomain;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.NoopBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.args.FileListableLinkerInputArg;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.rules.coercer.FrameworkPath;
import com.facebook.buck.util.MoreCollectors;
import com.facebook.buck.util.RichStream;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * An action graph representation of a Swift library from the target graph, providing the various
 * interfaces to make it consumable by C/C native linkable rules.
 */
class SwiftLibrary extends NoopBuildRuleWithDeclaredAndExtraDeps
    implements HasRuntimeDeps, NativeLinkable, CxxPreprocessorDep {

  private final LoadingCache<CxxPlatform, ImmutableMap<BuildTarget, CxxPreprocessorInput>>
      transitiveCxxPreprocessorInputCache =
          CxxPreprocessables.getTransitiveCxxPreprocessorInputCache(this);

  private final BuildRuleResolver ruleResolver;

  private final Collection<? extends BuildRule> exportedDeps;
  private final ImmutableSet<FrameworkPath> frameworks;
  private final ImmutableSet<FrameworkPath> libraries;
  private final FlavorDomain<SwiftPlatform> swiftPlatformFlavorDomain;
  private final Optional<Pattern> supportedPlatformsRegex;
  private final Linkage linkage;

  SwiftLibrary(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams params,
      BuildRuleResolver ruleResolver,
      Collection<? extends BuildRule> exportedDeps,
      FlavorDomain<SwiftPlatform> swiftPlatformFlavorDomain,
      ImmutableSet<FrameworkPath> frameworks,
      ImmutableSet<FrameworkPath> libraries,
      Optional<Pattern> supportedPlatformsRegex,
      Linkage linkage) {
    super(buildTarget, projectFilesystem, params);
    this.ruleResolver = ruleResolver;
    this.exportedDeps = exportedDeps;
    this.frameworks = frameworks;
    this.libraries = libraries;
    this.swiftPlatformFlavorDomain = swiftPlatformFlavorDomain;
    this.supportedPlatformsRegex = supportedPlatformsRegex;
    this.linkage = linkage;
  }

  private boolean isPlatformSupported(CxxPlatform cxxPlatform) {
    return !supportedPlatformsRegex.isPresent()
        || supportedPlatformsRegex.get().matcher(cxxPlatform.getFlavor().toString()).find();
  }

  @Override
  public Iterable<NativeLinkable> getNativeLinkableDeps() {
    // TODO(beng, markwang): Use pseudo targets to represent the Swift
    // runtime library's linker args here so NativeLinkables can
    // deduplicate the linker flags on the build target (which would be the same for
    // all libraries).
    return RichStream.from(getDeclaredDeps())
        .filter(NativeLinkable.class)
        .collect(MoreCollectors.toImmutableSet());
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDeps() {
    throw new RuntimeException(
        "SwiftLibrary does not support getting linkable exported deps "
            + "without a specific platform.");
  }

  @Override
  public Iterable<? extends NativeLinkable> getNativeLinkableExportedDepsForPlatform(
      CxxPlatform cxxPlatform) {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableList.of();
    }
    SwiftRuntimeNativeLinkable swiftRuntimeNativeLinkable =
        new SwiftRuntimeNativeLinkable(swiftPlatformFlavorDomain.getValue(cxxPlatform.getFlavor()));
    return RichStream.from(exportedDeps)
        .filter(NativeLinkable.class)
        .concat(RichStream.of(swiftRuntimeNativeLinkable))
        .collect(MoreCollectors.toImmutableSet());
  }

  @Override
  public NativeLinkableInput getNativeLinkableInput(
      CxxPlatform cxxPlatform,
      Linker.LinkableDepType type,
      boolean forceLinkWhole,
      ImmutableSet<NativeLinkable.LanguageExtensions> languageExtensions)
      throws NoSuchBuildTargetException {
    SwiftCompile rule = requireSwiftCompileRule(cxxPlatform.getFlavor());
    NativeLinkableInput.Builder inputBuilder = NativeLinkableInput.builder();
    inputBuilder
        .addAllArgs(rule.getAstLinkArgs())
        .addAllFrameworks(frameworks)
        .addAllLibraries(libraries);
    boolean isDynamic;
    Linkage preferredLinkage = getPreferredLinkage(cxxPlatform);
    switch (preferredLinkage) {
      case STATIC:
        isDynamic = false;
        break;
      case SHARED:
        isDynamic = true;
        break;
      case ANY:
        isDynamic = type == Linker.LinkableDepType.SHARED;
        break;
      default:
        throw new IllegalStateException("unhandled linkage type: " + preferredLinkage);
    }

    if (isDynamic) {
      CxxLink swiftLinkRule = requireSwiftLinkRule(cxxPlatform.getFlavor());
      inputBuilder.addArgs(
          FileListableLinkerInputArg.withSourcePathArg(
              SourcePathArg.of(swiftLinkRule.getSourcePathToOutput())));
    } else {
      inputBuilder.addArgs(rule.getFileListLinkArg());
    }
    return inputBuilder.build();
  }

  @Override
  public ImmutableMap<String, SourcePath> getSharedLibraries(CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException {
    if (!isPlatformSupported(cxxPlatform)) {
      return ImmutableMap.of();
    }
    ImmutableMap.Builder<String, SourcePath> libs = ImmutableMap.builder();
    BuildRule sharedLibraryBuildRule = requireSwiftLinkRule(cxxPlatform.getFlavor());
    String sharedLibrarySoname =
        CxxDescriptionEnhancer.getSharedLibrarySoname(
            Optional.empty(), sharedLibraryBuildRule.getBuildTarget(), cxxPlatform);
    libs.put(sharedLibrarySoname, sharedLibraryBuildRule.getSourcePathToOutput());
    return libs.build();
  }

  SwiftCompile requireSwiftCompileRule(Flavor... flavors) {
    BuildTarget requiredBuildTarget =
        getBuildTarget()
            .withAppendedFlavors(flavors)
            .withoutFlavors(ImmutableSet.of(CxxDescriptionEnhancer.SHARED_FLAVOR))
            .withoutFlavors(ImmutableSet.of(SWIFT_COMPANION_FLAVOR))
            .withoutFlavors(LinkerMapMode.FLAVOR_DOMAIN.getFlavors())
            .withAppendedFlavors(SWIFT_COMPILE_FLAVOR);

    // Find the correct rule. Since the SwiftCompile rules are generated by buck itself, any
    // failures in finding the rule is a buck internal error.
    BuildRule rule;
    try {
      rule = ruleResolver.requireRule(requiredBuildTarget);
    } catch (NoSuchBuildTargetException e) {
      throw new IllegalStateException(
          String.format(
              "Failed to load swift compile rule from swift library meta-rule, "
                  + "no rule was found for target: %s",
              requiredBuildTarget.toString()),
          e);
    }
    try {
      return (SwiftCompile) rule;
    } catch (ClassCastException e) {
      throw new IllegalStateException(
          String.format(
              "Failed to load swift compile rule from swift library meta-rule, "
                  + "the retrieved rule was not a SwiftCompile. Target: %s",
              requiredBuildTarget.toString()),
          e);
    }
  }

  private CxxLink requireSwiftLinkRule(Flavor... flavors) throws NoSuchBuildTargetException {
    BuildTarget requiredBuildTarget =
        getBuildTarget()
            .withoutFlavors(SWIFT_COMPANION_FLAVOR)
            .withAppendedFlavors(CxxDescriptionEnhancer.SHARED_FLAVOR)
            .withAppendedFlavors(flavors);
    BuildRule rule = ruleResolver.requireRule(requiredBuildTarget);
    if (!(rule instanceof CxxLink)) {
      throw new RuntimeException(
          String.format("Could not find CxxLink with target %s", requiredBuildTarget));
    }
    return (CxxLink) rule;
  }

  @Override
  public NativeLinkable.Linkage getPreferredLinkage(CxxPlatform cxxPlatform) {
    // don't create dylib for swift companion target.
    if (getBuildTarget().getFlavors().contains(SWIFT_COMPANION_FLAVOR)) {
      return Linkage.STATIC;
    } else {
      return linkage;
    }
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps(SourcePathRuleFinder ruleFinder) {
    // We export all declared deps as runtime deps, to setup a transitive runtime dep chain which
    // will pull in runtime deps (e.g. other binaries) or transitive C/C++ libraries.  Since the
    // `CxxLibrary` rules themselves are noop meta rules, they shouldn't add any unnecessary
    // overhead.
    return Stream.concat(
            getDeclaredDeps().stream(), StreamSupport.stream(exportedDeps.spliterator(), false))
        .map(BuildRule::getBuildTarget);
  }

  @Override
  public Iterable<CxxPreprocessorDep> getCxxPreprocessorDeps(CxxPlatform cxxPlatform) {
    return getBuildDeps()
        .stream()
        .filter(CxxPreprocessorDep.class::isInstance)
        .map(CxxPreprocessorDep.class::cast)
        .collect(MoreCollectors.toImmutableSet());
  }

  @Override
  public CxxPreprocessorInput getCxxPreprocessorInput(CxxPlatform cxxPlatform)
      throws NoSuchBuildTargetException {
    if (!isPlatformSupported(cxxPlatform)) {
      return CxxPreprocessorInput.EMPTY;
    }

    BuildRule rule = requireSwiftCompileRule(cxxPlatform.getFlavor());

    return CxxPreprocessorInput.builder()
        .addIncludes(
            CxxHeadersDir.of(CxxPreprocessables.IncludeType.LOCAL, rule.getSourcePathToOutput()))
        .build();
  }

  @Override
  public ImmutableMap<BuildTarget, CxxPreprocessorInput> getTransitiveCxxPreprocessorInput(
      CxxPlatform cxxPlatform) throws NoSuchBuildTargetException {
    if (getBuildTarget().getFlavors().contains(SWIFT_COMPANION_FLAVOR)) {
      return ImmutableMap.of(getBuildTarget(), getCxxPreprocessorInput(cxxPlatform));
    } else {
      return transitiveCxxPreprocessorInputCache.getUnchecked(cxxPlatform);
    }
  }
}
