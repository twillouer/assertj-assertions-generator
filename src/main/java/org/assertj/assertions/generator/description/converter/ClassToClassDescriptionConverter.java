/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 * 
 * Copyright @2010-2011 the original author or authors.
 */
package org.assertj.assertions.generator.description.converter;

import static org.assertj.assertions.generator.description.TypeName.JAVA_LANG_PACKAGE;
import static org.assertj.assertions.generator.util.ClassUtil.*;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.lang.reflect.*;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.assertj.assertions.generator.description.ClassDescription;
import org.assertj.assertions.generator.description.GetterDescription;
import org.assertj.assertions.generator.description.TypeDescription;
import org.assertj.assertions.generator.description.TypeName;

import com.google.common.annotations.VisibleForTesting;

public class ClassToClassDescriptionConverter implements ClassDescriptionConverter<Class<?>> {

  public ClassDescription convertToClassDescription(Class<?> clazz) {
    ClassDescription classDescription = new ClassDescription(new TypeName(clazz));
    classDescription.addGetterDescriptions(getterDescriptionsOf(clazz));
    classDescription.addTypeToImport(getNeededImportsFor(clazz));
    return classDescription;
  }

  @VisibleForTesting
  protected Set<GetterDescription> getterDescriptionsOf(Class<?> clazz) {
    Set<GetterDescription> getterDescriptions = new TreeSet<GetterDescription>();
    List<Method> getters = getterMethodsOf(clazz);
    for (Method getter : getters) {
      final TypeDescription typeDescription = getTypeDescription(clazz, getter);
      List<TypeName> exceptions = new ArrayList<TypeName>();
      for (Class<?> exception : getter.getExceptionTypes()) {
          exceptions.add(new TypeName(exception));
      }
      getterDescriptions.add(new GetterDescription(propertyNameOf(getter), typeDescription, exceptions));
    }
    return getterDescriptions;
  }

  @VisibleForTesting
  protected TypeDescription getTypeDescription(Class<?> clazz, Method getter) {
    final Class<?> propertyType = getter.getReturnType();
    final TypeDescription typeDescription = new TypeDescription(new TypeName(propertyType));
    if (propertyType.isArray()) {
      typeDescription.setElementTypeName(new TypeName(propertyType.getComponentType()));
      typeDescription.setArray(true);
    } else if (isIterable(propertyType)) {
      ParameterizedType parameterizedType = (ParameterizedType) getter.getGenericReturnType();
      if (parameterizedType.getActualTypeArguments()[0] instanceof GenericArrayType) {
        GenericArrayType genericArrayType = (GenericArrayType) parameterizedType.getActualTypeArguments()[0];
        Class<?> parameterClass = getClass(genericArrayType.getGenericComponentType());
        typeDescription.setElementTypeName(new TypeName(parameterClass));
        typeDescription.setIterable(true);
        typeDescription.setArray(true);
      } else {
        Class<?> parameterClass = (Class<?>) parameterizedType.getActualTypeArguments()[0];
        typeDescription.setElementTypeName(new TypeName(parameterClass));
        typeDescription.setIterable(true);
      }
    }
    return typeDescription;
  }

  /**
   * Get the underlying class for a type, or null if the type is a variable type.
   * 
   * @param type the type
   * @return the underlying class
   */
  public static Class<?> getClass(final Type type) {
    if (type instanceof Class) {
      return (Class<?>) type;
    } else if (type instanceof ParameterizedType) {
      return getClass(((ParameterizedType) type).getRawType());
    } else if (type instanceof GenericArrayType) {
      final Type componentType = ((GenericArrayType) type).getGenericComponentType();
      final Class<?> componentClass = getClass(componentType);
      if (componentClass != null) {
        return Array.newInstance(componentClass, 0).getClass();
      } else {
        return null;
      }
    } else if (type instanceof WildcardType) {
      final WildcardType wildcard = (WildcardType) type;
      return wildcard.getUpperBounds() != null ? getClass(wildcard.getUpperBounds()[0])
          : wildcard.getLowerBounds() != null ? getClass(wildcard.getLowerBounds()[0]) : null;
    } else {
      return null;
    }
  }

  @VisibleForTesting
  protected Set<TypeName> getNeededImportsFor(Class<?> clazz) {
    // collect property types
    Set<Class<?>> classesToImport = new HashSet<Class<?>>();
    for (Method getter : getterMethodsOf(clazz)) {
      Class<?> propertyType = getter.getReturnType();
      if (propertyType.isArray()) {
        // we only need the component type, that is T in T[] array
        classesToImport.add(propertyType.getComponentType());
      } else if (isIterable(propertyType)) {
        // we need the Iterable parameter type, that is T in Iterable<T>
        // we don't need to import the Iterable since it does not appear directly in generated code, ex :
        // assertThat(actual.getTeamMates()).contains(teamMates); // teamMates -> List
        ParameterizedType parameterizedType = (ParameterizedType) getter.getGenericReturnType();
        if (parameterizedType.getActualTypeArguments()[0] instanceof GenericArrayType) {
          //
          GenericArrayType genericArrayType = (GenericArrayType) parameterizedType.getActualTypeArguments()[0];
          Class<?> parameterClass = getClass(genericArrayType.getGenericComponentType());
          classesToImport.add(parameterClass);
        } else {
          Class<?> actualParameterClass = (Class<?>) parameterizedType.getActualTypeArguments()[0];
          classesToImport.add(actualParameterClass);
        }
      } else if (getter.getGenericReturnType() instanceof ParameterizedType) {
        // return type is generic type, add it and all its parameters type.
        ParameterizedType parameterizedType = (ParameterizedType) getter.getGenericReturnType();
        classesToImport.addAll(getClassesRelatedTo(parameterizedType));
      } else {
        // return type is not generic type, simply add it.
        classesToImport.add(propertyType);
      }

      for (Class<?> exceptionType : getter.getExceptionTypes()) {
        classesToImport.add(exceptionType);
      }
    }
    // convert to TypeName, excluding primitive or types in java.lang that don't need to be imported.
    Set<TypeName> typeToImports = new TreeSet<TypeName>();
    for (Class<?> propertyType : classesToImport) {
      // Package can be null in case of array of primitive.
      if (!propertyType.isPrimitive()
          && (propertyType.getPackage() != null && !JAVA_LANG_PACKAGE.equals(propertyType.getPackage().getName()))) {
        typeToImports.add(new TypeName(propertyType));
      }
    }
    return typeToImports;
  }

}
