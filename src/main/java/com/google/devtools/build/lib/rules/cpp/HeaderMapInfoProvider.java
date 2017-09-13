package com.google.devtools.build.lib.rules.cpp;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.analysis.TransitiveInfoProvider;
import com.google.devtools.build.lib.util.FileTypeSet;

public class HeaderMapInfoProvider implements TransitiveInfoProvider {
  private final ImmutableList<String> sources;
  private final String namespace;

  public String getNamespace() {
    return namespace;
  }
  
  public ImmutableList<String> getSources(){
    return sources;   
  }

  private HeaderMapInfoProvider(ImmutableList<String> sources, String namespace) {
    this.sources = sources;
    this.namespace = namespace;
  }

  /** True if sources of the given type are used in this build. */
  public boolean uses(String source) {
    return sources.contains(source);
  }

  /** Builder for HeaderMapInfoProvider */
  public static class Builder {
    private final ImmutableList.Builder<String> sources = ImmutableList.builder();
    private String namespace;

    /** Signals that the build uses sources of the provided type. */
    public Builder addSource(String source) {
      this.sources.add(source);
      return this;
    }
    
    public Builder setNamespace(String namespace) {
      this.namespace = namespace;
      return this;
    }

    public HeaderMapInfoProvider build() {
      return new HeaderMapInfoProvider(this.sources.build(), this.namespace);
    }
  }
}
