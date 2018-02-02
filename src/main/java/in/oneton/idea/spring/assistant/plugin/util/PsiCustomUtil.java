package in.oneton.idea.spring.assistant.plugin.util;

import com.intellij.codeInsight.completion.InsertionContext;
import com.intellij.codeInsight.daemon.impl.analysis.JavaGenericsUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.psi.util.PropertyUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.TimeoutUtil;
import gnu.trove.THashMap;
import in.oneton.idea.spring.assistant.plugin.model.suggestion.SuggestionNodeType;
import in.oneton.idea.spring.assistant.plugin.model.suggestion.clazz.GenericClassMemberWrapper;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import static com.intellij.openapi.module.ModuleUtilCore.findModuleForFile;
import static com.intellij.openapi.module.ModuleUtilCore.findModuleForPsiElement;
import static com.intellij.openapi.util.Key.create;
import static com.intellij.psi.CommonClassNames.JAVA_LANG_ITERABLE;
import static com.intellij.psi.CommonClassNames.JAVA_LANG_STRING;
import static com.intellij.psi.CommonClassNames.JAVA_UTIL_MAP;
import static com.intellij.psi.JavaPsiFacade.getElementFactory;
import static com.intellij.psi.PsiModifier.PUBLIC;
import static com.intellij.psi.PsiModifier.STATIC;
import static com.intellij.psi.PsiPrimitiveType.getUnboxedType;
import static com.intellij.psi.util.CachedValueProvider.Result.create;
import static com.intellij.psi.util.CachedValuesManager.getCachedValue;
import static com.intellij.psi.util.InheritanceUtil.isInheritor;
import static com.intellij.psi.util.PropertyUtil.findPropertyFieldByMember;
import static com.intellij.psi.util.PropertyUtil.findPropertySetter;
import static com.intellij.psi.util.PropertyUtil.getPropertyName;
import static com.intellij.psi.util.PropertyUtil.isSimplePropertyGetter;
import static com.intellij.psi.util.PropertyUtil.isSimplePropertySetter;
import static com.intellij.psi.util.PsiModificationTracker.JAVA_STRUCTURE_MODIFICATION_COUNT;
import static com.intellij.psi.util.PsiTypesUtil.getClassType;
import static com.intellij.psi.util.PsiTypesUtil.hasUnresolvedComponents;
import static com.intellij.psi.util.PsiUtil.extractIterableTypeParameter;
import static com.intellij.psi.util.PsiUtil.resolveGenericsClassInType;
import static com.intellij.util.containers.ContainerUtil.isEmpty;
import static in.oneton.idea.spring.assistant.plugin.model.suggestion.SuggestionNode.sanitise;
import static in.oneton.idea.spring.assistant.plugin.model.suggestion.SuggestionNodeType.ARRAY;
import static in.oneton.idea.spring.assistant.plugin.model.suggestion.SuggestionNodeType.BOOLEAN;
import static in.oneton.idea.spring.assistant.plugin.model.suggestion.SuggestionNodeType.BYTE;
import static in.oneton.idea.spring.assistant.plugin.model.suggestion.SuggestionNodeType.CHAR;
import static in.oneton.idea.spring.assistant.plugin.model.suggestion.SuggestionNodeType.DOUBLE;
import static in.oneton.idea.spring.assistant.plugin.model.suggestion.SuggestionNodeType.ENUM;
import static in.oneton.idea.spring.assistant.plugin.model.suggestion.SuggestionNodeType.FLOAT;
import static in.oneton.idea.spring.assistant.plugin.model.suggestion.SuggestionNodeType.INT;
import static in.oneton.idea.spring.assistant.plugin.model.suggestion.SuggestionNodeType.ITERABLE;
import static in.oneton.idea.spring.assistant.plugin.model.suggestion.SuggestionNodeType.KNOWN_CLASS;
import static in.oneton.idea.spring.assistant.plugin.model.suggestion.SuggestionNodeType.LONG;
import static in.oneton.idea.spring.assistant.plugin.model.suggestion.SuggestionNodeType.MAP;
import static in.oneton.idea.spring.assistant.plugin.model.suggestion.SuggestionNodeType.SHORT;
import static in.oneton.idea.spring.assistant.plugin.model.suggestion.SuggestionNodeType.STRING;
import static in.oneton.idea.spring.assistant.plugin.model.suggestion.SuggestionNodeType.UNDEFINED;
import static in.oneton.idea.spring.assistant.plugin.model.suggestion.SuggestionNodeType.UNKNOWN_CLASS;
import static java.util.Objects.requireNonNull;

@UtilityClass
public class PsiCustomUtil {
  private static final Logger log = Logger.getInstance(PsiCustomUtil.class);

  @Nullable
  public static PsiType safeGetValidType(@NotNull Module module, @NotNull String fqn) {
    try {
      PsiType type = JavaPsiFacade.getInstance(module.getProject()).getParserFacade()
          .createTypeFromText(fqn, null);
      boolean typeValid = isValidType(type);
      if (typeValid) {
        if (type instanceof PsiClassType) {
          return PsiClassType.class.cast(type);
        } else if (type instanceof PsiArrayType) {
          return PsiArrayType.class.cast(type);
        }
      }
      return null;
    } catch (IncorrectOperationException e) {
      debug(() -> log.debug("Unable to find class fqn " + fqn));
      return null;
    }
  }

  @Nullable
  public static PsiClass getContainingClass(PsiElement psiElement) {
    if (psiElement instanceof PsiField) {
      return ((PsiField) psiElement).getContainingClass();
    }
    if (psiElement instanceof PsiMethod) {
      return ((PsiMethod) psiElement).getContainingClass();
    }
    throw new RuntimeException("Method supports psiElement of type PsiField & PsiMethod only");
  }

  @NotNull
  public static PsiType getReferredPsiType(PsiElement psiElement) {
    if (psiElement instanceof PsiField) {
      return ((PsiField) psiElement).getType();
    } else if (psiElement instanceof PsiMethod) {
      return requireNonNull(((PsiMethod) psiElement).getReturnType());
    } else if (psiElement instanceof PsiClass) {
      return getClassType((PsiClass) psiElement);
    }
    throw new RuntimeException(
        "Method supports psiElement of type PsiField, PsiMethod & PsiClass only");
  }

  public static PsiType getFirstTypeParameter(PsiClassType psiClassType) {
    PsiClassType.ClassResolveResult resolveResult = psiClassType.resolveGenerics();
    if (resolveResult.isValidResult()) {
      Collection<PsiType> values = resolveResult.getSubstitutor().getSubstitutionMap().values();
      if (!isEmpty(values)) {
        return values.iterator().next();
      }
    }
    return null;
  }

  @Nullable
  public static Collection<PsiType> getTypeParameters(@NotNull PsiElement psiElement) {
    PsiType psiType = getReferredPsiType(psiElement);
    return getTypeParameters(psiType);
  }

  @Nullable
  public static Collection<PsiType> getTypeParameters(PsiType type) {
    if (type instanceof PsiArrayType) {
      return getTypeParameters(((PsiArrayType) type).getComponentType());
    } else if (type instanceof PsiPrimitiveType) {
      return null;
    } else if (type instanceof PsiClassType) {
      // TODO: Not sure how this behaves when some classes can be resolved while others cannot
      PsiClassType.ClassResolveResult resolveResult =
          PsiClassType.class.cast(type).resolveGenerics();
      if (resolveResult.isValidResult()) {
        return resolveResult.getSubstitutor().getSubstitutionMap().values();
      }
    }
    return null;
  }

  @NotNull
  public static Optional<PsiField> findSettablePsiField(@NotNull PsiClass clazz,
      @Nullable String propertyName) {
    PsiMethod propertySetter = findPropertySetter(clazz, propertyName, false, true);
    return null == propertySetter ?
        Optional.empty() :
        Optional.ofNullable(findPropertyFieldByMember(propertySetter));
  }

  @NotNull
  public static SuggestionNodeType getSuggestionNodeType(PsiType type) {
    if (type == null) {
      return UNDEFINED;
    } else if (type instanceof PsiArrayType) {
      return ARRAY;
    } else if (type instanceof PsiPrimitiveType) {
      SuggestionNodeType nodeType = getSuggestionNodeTypeForPrimitive(type);
      return nodeType != null ? nodeType : UNKNOWN_CLASS;
    } else if (type instanceof PsiClassType) {
      SuggestionNodeType nodeType = getSuggestionNodeTypeForPrimitive(type);
      if (nodeType != null) {
        return nodeType;
      } else if (type.getCanonicalText().equals(JAVA_LANG_STRING)) {
        return STRING;
      }

      // TODO: Need to check if this is required or not?
      PsiClassType psiClassType = (PsiClassType) type;
      PsiClassType.ClassResolveResult classResolveResult = psiClassType.resolveGenerics();
      if (classResolveResult.isValidResult()) {
        PsiClass psiClass = requireNonNull(classResolveResult.getElement());
        if (psiClass.isEnum()) {
          return ENUM;
        } else if (isMap(psiClass)) {
          return MAP;
        } else if (isIterable(psiClass)) {
          return ITERABLE;
        } else {
          return KNOWN_CLASS;
        }
      }
    }
    return UNKNOWN_CLASS;
  }

  @Nullable
  private static SuggestionNodeType getSuggestionNodeTypeForPrimitive(PsiType type) {
    if (PsiType.BOOLEAN.equals(type) || PsiType.BOOLEAN
        .equals(PsiPrimitiveType.getUnboxedType(type))) {
      return BOOLEAN;
    } else if (PsiType.BYTE.equals(type) || PsiType.BYTE
        .equals(PsiPrimitiveType.getUnboxedType(type))) {
      return BYTE;
    } else if (PsiType.SHORT.equals(type) || PsiType.SHORT
        .equals(PsiPrimitiveType.getUnboxedType(type))) {
      return SHORT;
    } else if (PsiType.INT.equals(type) || PsiType.INT
        .equals(PsiPrimitiveType.getUnboxedType(type))) {
      return INT;
    } else if (PsiType.LONG.equals(type) || PsiType.LONG
        .equals(PsiPrimitiveType.getUnboxedType(type))) {
      return LONG;
    } else if (PsiType.FLOAT.equals(type) || PsiType.FLOAT
        .equals(PsiPrimitiveType.getUnboxedType(type))) {
      return FLOAT;
    } else if (PsiType.DOUBLE.equals(type) || PsiType.DOUBLE
        .equals(PsiPrimitiveType.getUnboxedType(type))) {
      return DOUBLE;
    } else if (PsiType.CHAR.equals(type) || PsiType.CHAR
        .equals(PsiPrimitiveType.getUnboxedType(type))) {
      return CHAR;
    }
    return null;
  }

  @Nullable
  public static String toClassFqn(@NotNull PsiType type) {
    if (type instanceof PsiArrayType) {
      String componentLongName = toClassFqn(PsiArrayType.class.cast(type).getComponentType());
      if (componentLongName != null) {
        return componentLongName + "[]";
      }
    } else if (type instanceof PsiPrimitiveType) {
      return type.getPresentableText();
    } else if (type instanceof PsiClassType) {
      PsiClass psiClass = toValidPsiClass((PsiClassType) type);
      if (psiClass != null) {
        return psiClass.getQualifiedName();
      }
    }
    return null;
  }

  @Nullable
  public static String toClassNonQualifiedName(@NotNull PsiType type) {
    if (type instanceof PsiArrayType) {
      String componentLongName =
          toClassNonQualifiedName(PsiArrayType.class.cast(type).getComponentType());
      if (componentLongName != null) {
        return componentLongName + "[]";
      }
    } else if (type instanceof PsiPrimitiveType) {
      return type.getPresentableText();
    } else if (type instanceof PsiClassType) {
      return ((PsiClassType) type).getClassName();
    }
    return null;
  }

  private static boolean isMap(@NotNull PsiClass psiClass) {
    return isClassSameOrDescendantOf(psiClass, JAVA_UTIL_MAP);
  }

  private static boolean isIterable(@NotNull PsiClass psiClass) {
    return isClassSameOrDescendantOf(psiClass, JAVA_LANG_ITERABLE);
  }

  private static boolean isClassSameOrDescendantOf(@NotNull PsiClass psiClass,
      String expectedClassFqn) {
    return psiClass.getQualifiedName() != null && isInheritor(psiClass, expectedClassFqn);
  }

  @Contract("null->false")
  public static boolean isPrimitiveOrBoxed(@Nullable PsiType psiType) {
    return psiType instanceof PsiPrimitiveType || getUnboxedType(psiType) != null;
  }

  @Nullable
  public static PsiClass toValidPsiClass(@NotNull PsiClassType type) {
    if (isValidType(type)) {
      return type.resolve();
    }
    return null;
  }

  // Copied & modified from PsiUtil.ensureValidType
  public static boolean isValidType(@NotNull PsiType type) {
    if (type instanceof PsiArrayType) {
      type = PsiArrayType.class.cast(type).getComponentType();
    }
    if (!type.isValid()) {
      TimeoutUtil.sleep(
          1); // to see if processing in another thread suddenly makes the type valid again (which is a bug)
      if (type.isValid()) {
        return true;
      }
      if (type instanceof PsiClassType) {
        PsiClassType.ClassResolveResult classResolveResult =
            ((PsiClassType) type).resolveGenerics();
        return classResolveResult.isValidResult() && isValidElement(
            requireNonNull(classResolveResult.getElement())) && !hasUnresolvedComponents(type);
      }
      return false;
    }
    return true;
  }

  /**
   * Checks if the element is valid. If not, throws {@link com.intellij.psi.PsiInvalidElementAccessException} with
   * a meaningful message that points to the reasons why the element is not valid and may contain the stack trace
   * when it was invalidated.
   */
  // Copied & modified from PsiUtilCore.ensureValid
  private static boolean isValidElement(@NotNull PsiElement element) {
    if (!element.isValid()) {
      TimeoutUtil.sleep(
          1); // to see if processing in another thread suddenly makes the element valid again (which is a bug)
      return element.isValid();
    }
    return true;
  }

  @Nullable
  public static PsiType getComponentType(@NotNull PsiType type) {
    if (type instanceof PsiArrayType) {
      return ((PsiArrayType) type).getComponentType();
    }
    return extractIterableTypeParameter(type, true);
  }

  @Contract("_, null->false")
  private static boolean representsCollection(@NotNull PsiClass psiClass, @Nullable PsiType type) {
    return type != null && getCollectionItemType(psiClass, type) != null;
  }

  @Nullable
  private static PsiType getCollectionItemType(@NotNull PsiClass psiClass, @NotNull PsiType type) {
    return JavaGenericsUtil.getCollectionItemType(type, psiClass.getResolveScope());
  }

  @Nullable
  public static Map<String, GenericClassMemberWrapper> getSanitisedPropertyToPsiMemberWrapper(
      @Nullable PsiClass psiClass) {
    if (psiClass != null) {
      return getCachedValue(psiClass,
          create("spring_assistant_plugin_property_to_class_member_wrapper"),
          () -> create(prepareWritableProperties(psiClass), JAVA_STRUCTURE_MODIFICATION_COUNT));
    }
    return null;
  }

  @NotNull
  private static Map<String, GenericClassMemberWrapper> prepareWritableProperties(
      @NotNull PsiClass psiClass) {
    final Map<String, GenericClassMemberWrapper> memberNameToMemberWrapper = new THashMap<>();
    for (PsiMethod method : psiClass.getAllMethods()) {
      if (method.hasModifierProperty(STATIC) || !method.hasModifierProperty(PUBLIC)) {
        continue;
      }
      if (isSimplePropertyGetter(method)) {
        PsiMember acceptableMember = method;
        final String propertyName = getPropertyName(method);
        assert propertyName != null;

        PsiMethod setter = findInstancePropertySetter(psiClass, propertyName);
        if (setter != null) {
          final PsiType setterArgType = setter.getParameterList().getParameters()[0].getType();
          final PsiField field = psiClass.findFieldByName(propertyName, true);
          if (field != null && !field.hasModifierProperty(STATIC)) {
            final PsiType fieldType = getWritablePropertyType(psiClass, field);
            if (fieldType == null || setterArgType.isConvertibleFrom(fieldType)) {
              acceptableMember = field;
            }
          }
        } else {
          final PsiType returnType = method.getReturnType();
          if (returnType != null && representsCollection(psiClass, returnType)) {
            final PsiField field = psiClass.findFieldByName(propertyName, true);
            if (field != null && !field.hasModifierProperty(STATIC)) {
              final PsiType fieldType = getWritablePropertyType(psiClass, field);
              if (fieldType == null || returnType.isAssignableFrom(fieldType)) {
                acceptableMember = field;
              }
            }
          } else {
            acceptableMember = null;
          }
        }
        if (acceptableMember != null)
          memberNameToMemberWrapper
              .put(sanitise(propertyName), new GenericClassMemberWrapper(acceptableMember));
      }
    }
    return memberNameToMemberWrapper;
  }

  @Nullable
  public static PsiType getWritablePropertyType(@Nullable PsiClass containingClass,
      @Nullable PsiElement declaration) {
    if (declaration instanceof PsiField) {
      return getFieldType((PsiField) declaration);
    }
    if (declaration instanceof PsiMethod) {
      final PsiMethod method = (PsiMethod) declaration;
      if (method.getParameterList().getParametersCount() != 0) {
        return getSetterArgumentType(method);
      }
      final String propertyName = getPropertyName(method);
      final PsiClass psiClass =
          containingClass != null ? containingClass : method.getContainingClass();
      if (propertyName != null && containingClass != null) {
        final PsiMethod setter = findInstancePropertySetter(psiClass, propertyName);
        if (setter != null) {
          final PsiType setterArgumentType = getSetterArgumentType(setter);
          if (setterArgumentType != null)
            return setterArgumentType;
        }
      }
      return getGetterReturnType(method);
    }
    return null;
  }

  @Nullable
  public static PsiType getFieldType(final PsiField field) {
    return getCachedValue(field, create("spring_assistant_plugin_eraseFreeTypeParameterType"),
        () -> {
          final PsiType fieldType = field.getType();
          final PsiClassType.ClassResolveResult resolveResult =
              resolveGenericsClassInType(fieldType);
          final PsiClass fieldClass = resolveResult.getElement();
          if (fieldClass == null) {
            final PsiType propertyType = eraseFreeTypeParameters(fieldType, field);
            return create(propertyType, JAVA_STRUCTURE_MODIFICATION_COUNT);
          }
          return null;
        });
  }

  @Nullable
  private static PsiType getSetterArgumentType(@NotNull PsiMethod method) {
    return getCachedValue(method, create("spring_assistant_plugin_firstParameterType"), () -> {
      final PsiParameter[] parameters = method.getParameterList().getParameters();
      if (!method.hasModifierProperty(STATIC) && parameters.length == 1) {
        final PsiType argumentType = eraseFreeTypeParameters(parameters[0].getType(), method);
        return create(argumentType, JAVA_STRUCTURE_MODIFICATION_COUNT);
      }
      return create(null, JAVA_STRUCTURE_MODIFICATION_COUNT);
    });
  }

  @Nullable
  private static PsiType eraseFreeTypeParameters(@Nullable PsiType psiType,
      @NotNull PsiMember member) {
    final PsiClass containingClass = member.getContainingClass();
    return eraseFreeTypeParameters(psiType, containingClass);
  }

  @Nullable
  private static PsiType eraseFreeTypeParameters(@Nullable PsiType psiType,
      @Nullable PsiClass containingClass) {
    if (containingClass == null) {
      return null;
    }
    return getElementFactory(containingClass.getProject()).createRawSubstitutor(containingClass)
        .substitute(psiType);
  }

  private static PsiType getGetterReturnType(@NotNull PsiMethod method) {
    return getCachedValue(method, create("spring_assistant_plugin_returnType"), () -> {
      final PsiType returnType = eraseFreeTypeParameters(method.getReturnType(), method);
      return create(returnType, JAVA_STRUCTURE_MODIFICATION_COUNT);
    });
  }

  @Nullable
  public static PsiMethod findInstancePropertySetter(@NotNull PsiClass psiClass,
      @Nullable String propertyName) {
    if (StringUtil.isEmpty(propertyName))
      return null;
    final String suggestedSetterName = PropertyUtil.suggestSetterName(propertyName);
    final PsiMethod[] setters = psiClass.findMethodsByName(suggestedSetterName, true);
    for (PsiMethod setter : setters) {
      if (setter.hasModifierProperty(PUBLIC) && !setter.hasModifierProperty(STATIC)
          && isSimplePropertySetter(setter)) {
        return setter;
      }
    }
    return null;
  }

  @Nullable
  public static Module findModule(@NotNull PsiElement element) {
    return findModuleForPsiElement(element);
  }

  @Nullable
  public static Module findModule(@NotNull InsertionContext context) {
    return findModuleForFile(context.getFile().getVirtualFile(), context.getProject());
  }

  @Nullable
  public static String computeDocumentation(PsiMember member) {
    PsiDocComment docComment = null;
    if (member instanceof PsiField) {
      docComment = PsiField.class.cast(member).getDocComment();
    } else if (member instanceof PsiMethod) {
      docComment = PsiMethod.class.cast(member).getDocComment();
    }
    if (docComment != null) {
      return docComment.getText();
    }
    throw new RuntimeException("Method supports targets of type PsiField & PsiMethod only");
  }

  /**
   * Debug logging can be enabled by adding fully classified class name/package name with # prefix
   * For eg., to enable debug logging, go `Help > Debug log settings` & type `#in.oneton.idea.spring.assistant.plugin.service.SuggestionServiceImpl`
   *
   * @param doWhenDebug code to execute when debug is enabled
   */
  private void debug(Runnable doWhenDebug) {
    if (log.isDebugEnabled()) {
      doWhenDebug.run();
    }
  }

}