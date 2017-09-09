package com.google.devtools.build.lib.rules.cpp;

import static com.google.devtools.build.lib.packages.Attribute.ConfigurationTransition.HOST;
import static com.google.devtools.build.lib.packages.Attribute.attr;

import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.AttributeMap;
import com.google.devtools.build.lib.packages.Rule;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.Builder;
import com.google.devtools.build.lib.syntax.Type;
import com.google.devtools.build.lib.util.FileType;

/**
 * Rule definition for the header_map rule.
 */
public class HeaderMapRule implements RuleDefinition {

  @Override
  public RuleClass build(Builder builder, final RuleDefinitionEnvironment env) {
    //TODO: What should the API be?
    return builder
        .setOutputToGenfiles()
        /* <!-- #BLAZE_RULE(header_map).ATTRIBUTE(map) -->
         The keys and values to write into the HeaderMap.
        <!-- #END_BLAZE_RULE.ATTRIBUTE -->*/
        .add(
            attr("map", Type.STRING_DICT)
         )
        .build();
  }


  @Override
  public Metadata getMetadata() {
    return RuleDefinition.Metadata.builder()
        .name("header_map")
        .factoryClass(HeaderMap.class)
        .ancestors(BaseRuleClasses.BaseRule.class)
        .build();
  }
}
/*<!-- #BLAZE_RULE (NAME = headermap, TYPE = BINARY, FAMILY = Cpp) -->

${IMPLICIT_OUTPUTS}

<!-- #END_BLAZE_RULE -->*/

