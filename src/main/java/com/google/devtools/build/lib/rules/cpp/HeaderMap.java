package com.google.devtools.build.lib.rules.cpp;

import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.actions.Root;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.TargetUtils;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.vfs.PathFragment;

import java.util.Map;
import java.util.regex.Pattern;


/** Implementation for the "header_map" rule. */
public class HeaderMap implements RuleConfiguredTargetFactory {
  @Override
  public ConfiguredTarget create(RuleContext ruleContext)
      throws RuleErrorException, InterruptedException {

    // Via Skylark, the user provides a representation of the hash table
    // that clang reads in
    Map<String, String> inputMap =
        ruleContext.attributes().get("map", Type.STRING_DICT);

    //TODO: needed?
    Map<String, String> executionInfo = Maps.newLinkedHashMap();
    executionInfo.putAll(TargetUtils.getExecutionInfo(ruleContext.getRule()));

    // Create output files: a .hmap based on the target name
    String targetName = ruleContext.getTarget().getName();
    Root root = ruleContext.getBinOrGenfilesDirectory();
    PathFragment path = PathFragment.create(targetName + ".hmap");
    Artifact out = ruleContext.getPackageRelativeArtifact(path, root);

    NestedSetBuilder<Artifact> filesBuilder = NestedSetBuilder.stableOrder();
    filesBuilder.add(out);
    NestedSet<Artifact> filesToBuild = filesBuilder.build();

    ruleContext.registerAction(
            new HeaderMapAction(ruleContext.getActionOwner(),
                                inputMap,
                                out)
            );
    RuleConfiguredTargetBuilder builder = new RuleConfiguredTargetBuilder(ruleContext)
        .setFilesToBuild(filesToBuild)
        // TODO: ObjcProvider?
        .addProvider(RunfilesProvider.class, RunfilesProvider.EMPTY);
    return builder.build();
  }
}
