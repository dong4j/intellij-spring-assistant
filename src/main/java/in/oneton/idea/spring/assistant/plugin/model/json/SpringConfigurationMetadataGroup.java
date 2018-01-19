package in.oneton.idea.spring.assistant.plugin.model.json;

import com.intellij.codeInsight.documentation.DocumentationManager;
import in.oneton.idea.spring.assistant.plugin.model.MetadataNode;
import in.oneton.idea.spring.assistant.plugin.model.Suggestion;
import in.oneton.idea.spring.assistant.plugin.model.ValueType;
import lombok.Data;
import lombok.EqualsAndHashCode;

import javax.annotation.Nullable;

import static in.oneton.idea.spring.assistant.plugin.Util.methodForDocumentationNavigation;
import static in.oneton.idea.spring.assistant.plugin.Util.typeForDocumentationNavigation;
import static in.oneton.idea.spring.assistant.plugin.model.ValueType.removeGenerics;
import static in.oneton.idea.spring.assistant.plugin.model.ValueType.shortenedType;

/**
 * Refer to https://docs.spring.io/spring-boot/docs/2.0.0/reference/htmlsingle/#configuration-metadata-group-attributes
 */
@Data
@EqualsAndHashCode(of = "name")
public class SpringConfigurationMetadataGroup {
  private String name;
  @Nullable
  private String type;
  @Nullable
  private String description;
  @Nullable
  private String sourceType;
  @Nullable
  private String sourceMethod;

  public String getDocumentation(MetadataNode propertyNode) {
    // Format for the documentation is as follows
    /*
     * <p><b>a.b.c</b> ({@link com.acme.Generic}<{@link com.acme.Class1}, {@link com.acme.Class2}>)</p>
     * <p>Long description</p>
     * or of this type
     * <p><b>Type</b> {@link com.acme.Array}[]</p>
     * <p><b>Declared at</b>{@link com.acme.GenericRemovedClass#method}></p> <-- only for groups with method info
     */
    StringBuilder builder =
        new StringBuilder().append("<b>").append(propertyNode.getFullPath()).append("</b>");

    if (type != null) {
      StringBuilder buffer = new StringBuilder();
      DocumentationManager
          .createHyperlink(buffer, typeForDocumentationNavigation(type), type, false);
      builder.append(" (").append(buffer.toString()).append(")");
    }

    if (description != null) {
      builder.append("<p>").append(description).append("</p>");
    }

    if (sourceType != null) {
      String sourceTypeInJavadocFormat = removeGenerics(sourceType);

      if (sourceMethod != null) {
        sourceTypeInJavadocFormat += ("." + sourceMethod);
      }

      // lets show declaration point only if does not match the type
      if (type == null || !sourceTypeInJavadocFormat.equals(type)) {
        StringBuilder buffer = new StringBuilder();
        DocumentationManager
            .createHyperlink(buffer, methodForDocumentationNavigation(sourceTypeInJavadocFormat),
                sourceTypeInJavadocFormat, false);
        sourceTypeInJavadocFormat = buffer.toString();
        builder.append("<p>Declared at ").append(sourceTypeInJavadocFormat).append("</p>");
      }
    }

    return builder.toString();
  }

  public Suggestion newSuggestion(MetadataNode ref, String suggestion) {
    return Suggestion.builder().icon(ValueType.parse(type, classLoader).getIcon(false))
        .suggestion(suggestion).description(description).shortType(shortenedType(type)).ref(ref)
        .build();
  }

}
