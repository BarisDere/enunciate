/*
 * Copyright 2006-2008 Web Cohesion
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.codehaus.enunciate.contract.jaxb;

import com.sun.mirror.apt.AnnotationProcessorEnvironment;
import com.sun.mirror.declaration.*;
import com.sun.mirror.type.*;
import com.sun.mirror.util.Declarations;
import net.sf.jelly.apt.Context;
import net.sf.jelly.apt.decorations.DeclarationDecorator;
import net.sf.jelly.apt.decorations.declaration.DecoratedClassDeclaration;
import net.sf.jelly.apt.decorations.declaration.DecoratedDeclaration;
import net.sf.jelly.apt.decorations.declaration.DecoratedMethodDeclaration;
import net.sf.jelly.apt.decorations.declaration.PropertyDeclaration;
import org.codehaus.enunciate.ClientName;
import org.codehaus.enunciate.contract.Facet;
import org.codehaus.enunciate.contract.HasFacets;
import org.codehaus.enunciate.contract.jaxb.types.XmlType;
import org.codehaus.enunciate.contract.validation.BaseValidator;
import org.codehaus.enunciate.contract.validation.ValidationException;
import org.codehaus.enunciate.contract.validation.ValidationResult;
import org.codehaus.enunciate.qname.XmlQNameEnumRef;

import javax.xml.bind.annotation.*;
import javax.xml.namespace.QName;
import java.beans.Introspector;
import java.util.*;

/**
 * A xml type definition.
 *
 * @author Ryan Heaton
 */
public abstract class TypeDefinition extends DecoratedClassDeclaration implements HasFacets {

  private final javax.xml.bind.annotation.XmlType xmlType;
  private final Schema schema;
  private final SortedSet<Element> elements;
  private final Collection<Attribute> attributes;
  private final Value xmlValue;
  private final Accessor xmlID;
  private final boolean hasAnyAttribute;
  private final TypeMirror anyAttributeQNameEnumRef;
  private final AnyElement anyElement;
  private final Set<String> referencedFrom = new TreeSet<String>();
  private final Set<Facet> facets = new TreeSet<Facet>();

  protected TypeDefinition(ClassDeclaration delegate) {
    super(delegate);

    this.xmlType = getAnnotation(javax.xml.bind.annotation.XmlType.class);
    Package pckg;
    try {
      //if this is an already-compiled class, APT has a problem looking up the package info on the classpath...
      pckg = Class.forName(getQualifiedName()).getPackage();
    }
    catch (Throwable e) {
      pckg = null;
    }
    this.schema = new Schema(delegate.getPackage(), pckg);

    ElementComparator comparator = new ElementComparator(getPropertyOrder(), getAccessorOrder());
    SortedSet<Element> elementAccessors = new TreeSet<Element>(comparator);
    AccessorFilter filter = new AccessorFilter(getAccessType());
    Collection<Attribute> attributeAccessors = new ArrayList<Attribute>();
    Value value = null;

    Accessor xmlID = null;
    AnyElement anyElement = null;
    boolean hasAnyAttribute = false;
    TypeMirror anyAttributeQNameEnumRef = null;
    for (MemberDeclaration accessor : loadPotentialAccessors(filter)) {
      Accessor added;
      if (isAttribute(accessor)) {
        Attribute attribute = new Attribute(accessor, this);
        attributeAccessors.add(attribute);
        added = attribute;
      }
      else if (isValue(accessor)) {
        if (value != null) {
          throw new ValidationException(accessor.getPosition(), "Accessor " + accessor.getSimpleName() + " of " + getQualifiedName() + ": a type definition cannot have more than one xml value.");
        }

        value = new Value(accessor, this);
        added = value;
      }
      else if (isElementRef(accessor)) {
        ElementRef elementRef = new ElementRef(accessor, this);
        if (!elementAccessors.add(elementRef)) {
          //see http://jira.codehaus.org/browse/ENUNCIATE-381; the case for this is when an annotated field has an associated public property
          //we'll just silently continue
          continue;
        }
        added = elementRef;
      }
      else if (isAnyAttribute(accessor)) {
        hasAnyAttribute = true;

        XmlQNameEnumRef enumRef = accessor.getAnnotation(XmlQNameEnumRef.class);
        if (enumRef != null) {
          AnnotationProcessorEnvironment env = Context.getCurrentEnvironment();
          try {
            TypeDeclaration decl = env.getTypeDeclaration(enumRef.value().getName());
            anyAttributeQNameEnumRef = env.getTypeUtils().getDeclaredType(decl);
          }
          catch (MirroredTypeException e) {
            anyAttributeQNameEnumRef = e.getTypeMirror();
          }
        }

        continue;
      }
      else if (isAnyElement(accessor)) {
        anyElement = new AnyElement(accessor, this);
        continue;
      }
      else if (isUnsupported(accessor)) {
        throw new ValidationException(accessor.getPosition(), "Accessor " + accessor.getSimpleName() + " of " + getQualifiedName() + ": sorry, we currently don't support mixed or wildard elements. Maybe someday...");
      }
      else {
        //its an element accessor.

        if (accessor instanceof PropertyDeclaration) {
          //if the accessor is a property and either the getter or setter overrides ANY method of ANY superclass, exclude it.
          if (overrides(((PropertyDeclaration) accessor).getGetter()) || overrides(((PropertyDeclaration) accessor).getSetter())) {
            continue;
          }
        }

        Element element = new Element(accessor, this);
        if (!elementAccessors.add(element)) {
          //see http://jira.codehaus.org/browse/ENUNCIATE-381; the case for this is when an annotated field has an associated public property
          //we'll just silently continue
          continue;
        }
        added = element;
      }

      if (added.getAnnotation(XmlID.class) != null) {
        if (xmlID != null) {
          throw new ValidationException(added.getPosition(), "Accessor " + added.getSimpleName() + " of " + getQualifiedName() + ": more than one XML id specified.");
        }

        xmlID = added;
      }
    }

    this.elements = Collections.unmodifiableSortedSet(elementAccessors);
    this.attributes = Collections.unmodifiableCollection(attributeAccessors);
    this.xmlValue = value;
    this.xmlID = xmlID;
    this.hasAnyAttribute = hasAnyAttribute;
    this.anyAttributeQNameEnumRef = anyAttributeQNameEnumRef;
    this.anyElement = anyElement;
    this.facets.addAll(Facet.gatherFacets(delegate));
    this.facets.addAll(this.schema.getFacets());
  }

  /**
   * Load the potential accessors for this type definition.
   *
   * @param filter The filter.
   * @return the potential accessors for this type definition.
   */
  protected List<MemberDeclaration> loadPotentialAccessors(AccessorFilter filter) {
    List<FieldDeclaration> potentialFields = new ArrayList<FieldDeclaration>();
    List<PropertyDeclaration> potentialProperties = new ArrayList<PropertyDeclaration>();
    aggregatePotentialAccessors(potentialFields, potentialProperties, this, filter, false);

    List<MemberDeclaration> accessors = new ArrayList<MemberDeclaration>();
    accessors.addAll(potentialFields);
    accessors.addAll(potentialProperties);
    return accessors;
  }

  /**
   * Aggregate the potential accessor into their separate buckets for the given class declaration, recursively including transient superclasses.
   *
   * @param fields     The fields.
   * @param properties The properties.
   * @param clazz      The class.
   * @param filter     The filter.
   */
  protected void aggregatePotentialAccessors(List<FieldDeclaration> fields, List<PropertyDeclaration> properties, DecoratedClassDeclaration clazz, AccessorFilter filter, boolean childIsXmlTransient) {
    DecoratedClassDeclaration superDeclaration = (clazz.getSuperclass() != null && clazz.getSuperclass().getDeclaration() != null) ?
      (DecoratedClassDeclaration) DeclarationDecorator.decorate(clazz.getSuperclass().getDeclaration()) :
      null;
    if (superDeclaration != null && (isXmlTransient(superDeclaration) || childIsXmlTransient)) {
      childIsXmlTransient = true;
      aggregatePotentialAccessors(fields, properties, superDeclaration, filter, childIsXmlTransient);
    }

    for (FieldDeclaration fieldDeclaration : clazz.getFields()) {
      if (!filter.accept(fieldDeclaration)) {
        remove(fieldDeclaration, fields);
      }
      else {
        addOrReplace(fieldDeclaration, fields);
      }
    }

    for (PropertyDeclaration propertyDeclaration : clazz.getProperties()) {
      if (!filter.accept(propertyDeclaration)) {
        remove(propertyDeclaration, properties);
      }
      else {
        addOrReplace(propertyDeclaration, properties);
      }
    }
  }

  /**
   * Whether the given method declaration overrides any method.
   *
   * @param method The method declaration.
   * @return Whether the given method declaration overrides any method.
   */
  protected boolean overrides(DecoratedMethodDeclaration method) {
    if (method == null) {
      return false;
    }
    
    AnnotationProcessorEnvironment env = Context.getCurrentEnvironment();
    Declarations decls = env.getDeclarationUtils();

    Declaration unwrappedMethod = method.getDelegate();
    while (unwrappedMethod instanceof DecoratedDeclaration) {
      unwrappedMethod = ((DecoratedDeclaration) unwrappedMethod).getDelegate();
    }

    TypeDeclaration declaringType = method.getDeclaringType();
    if (declaringType instanceof ClassDeclaration) {
      declaringType = ((ClassDeclaration) declaringType).getSuperclass().getDeclaration();
      while (declaringType instanceof ClassDeclaration && !Object.class.getName().equals(declaringType.getQualifiedName())) {
        Collection<? extends MethodDeclaration> methods = declaringType.getMethods();
        for (Declaration candidate : methods) {
          while (candidate instanceof DecoratedDeclaration) {
            //unwrap the candidate.
            candidate = ((DecoratedDeclaration) candidate).getDelegate();
          }

          if (decls.overrides((MethodDeclaration) candidate, (MethodDeclaration) unwrappedMethod)) {
            return true;
          }
        }

        declaringType = ((ClassDeclaration) declaringType).getSuperclass().getDeclaration();
      }
    }

    return false;
  }

  /**
   * Add the specified member declaration, or if it is already in the list (by name), replace it.
   *
   * @param memberDeclaration  The member to add/replace.
   * @param memberDeclarations The other members.
   */
  protected <M extends MemberDeclaration> void addOrReplace(M memberDeclaration, List<M> memberDeclarations) {
    remove(memberDeclaration, memberDeclarations);
    memberDeclarations.add(memberDeclaration);
  }

  /**
   * Remove specified member declaration from the specified list, if it exists..
   *
   * @param memberDeclaration  The member to remove.
   * @param memberDeclarations The other members.
   */
  protected <M extends MemberDeclaration> void remove(M memberDeclaration, List<M> memberDeclarations) {
    Iterator<M> it = memberDeclarations.iterator();
    while (it.hasNext()) {
      MemberDeclaration candidate = it.next();
      if (candidate.getSimpleName().equals(memberDeclaration.getSimpleName())) {
        it.remove();
      }
    }
  }

  /**
   * Whether a declaration is an xml attribute.
   *
   * @param declaration The declaration to check.
   * @return Whether a declaration is an attribute.
   */
  protected boolean isAttribute(MemberDeclaration declaration) {
    //todo: the attribute wildcard?
    return (declaration.getAnnotation(XmlAttribute.class) != null);
  }

  /**
   * Whether a declaration is an xml value.
   *
   * @param declaration The declaration to check.
   * @return Whether a declaration is an value.
   */
  protected boolean isValue(MemberDeclaration declaration) {
    return (declaration.getAnnotation(XmlValue.class) != null);
  }

  /**
   * Whether a declaration is an xml element ref.
   *
   * @param declaration The declaration to check.
   * @return Whether a declaration is an xml element ref.
   */
  protected boolean isElementRef(MemberDeclaration declaration) {
    return ((declaration.getAnnotation(XmlElementRef.class) != null) || (declaration.getAnnotation(XmlElementRefs.class) != null));
  }

  /**
   * Whether the member declaration is XmlAnyAttribute.
   *
   * @param declaration The declaration.
   * @return Whether the member declaration is XmlAnyAttribute.
   */
  protected boolean isAnyAttribute(MemberDeclaration declaration) {
    return declaration.getAnnotation(XmlAnyAttribute.class) != null;
  }

  /**
   * Whether the member declaration is XmlAnyElement.
   *
   * @param declaration The declaration.
   * @return Whether the member declaration is XmlAnyElement.
   */
  protected boolean isAnyElement(MemberDeclaration declaration) {
    return declaration.getAnnotation(XmlAnyElement.class) != null;
  }

  /**
   * Whether a declaration is an xml-mixed property.
   *
   * @param declaration The declaration to check.
   * @return Whether a declaration is an mixed.
   */
  protected boolean isUnsupported(MemberDeclaration declaration) {
    //todo: support xml-mixed?
    return (declaration.getAnnotation(XmlMixed.class) != null);
  }

  /**
   * The name of the xml type element.
   *
   * @return The name of the xml type element.
   */
  public String getName() {
    String name = Introspector.decapitalize(getSimpleName());

    if ((xmlType != null) && (!"##default".equals(xmlType.name()))) {
      name = xmlType.name();

      if ("".equals(name)) {
        name = null;
      }
    }

    return name;
  }

  /**
   * The namespace of the xml type element.
   *
   * @return The namespace of the xml type element.
   */
  public String getNamespace() {
    String namespace = getPackage().getNamespace();

    if ((xmlType != null) && (!"##default".equals(xmlType.namespace()))) {
      namespace = xmlType.namespace();
    }

    return namespace;
  }

  /**
   * The simple name for client-side code generation.
   *
   * @return The simple name for client-side code generation.
   */
  public String getClientSimpleName() {
    String clientSimpleName = getSimpleName();
    ClientName clientName = getAnnotation(ClientName.class);
    if (clientName != null) {
      clientSimpleName = clientName.value();
    }
    return clientSimpleName;
  }

  /**
   * The qname of this type definition.
   *
   * @return The qname of this type definition.
   */
  public QName getQname() {
    String localPart = getName();
    if (localPart == null) {
      localPart = "";
    }
    return new QName(getNamespace(), localPart);
  }

  /**
   * The default access type for the beans in this class.
   *
   * @return The default access type for the beans in this class.
   */
  public XmlAccessType getAccessType() {
    XmlAccessType accessType = getPackage().getAccessType();

    XmlAccessorType xmlAccessorType = getAnnotation(XmlAccessorType.class);
    if (xmlAccessorType != null) {
      accessType = xmlAccessorType.value();
    }
    else {
      XmlAccessType inheritedAccessType = getInheritedAccessType(this);
      if (inheritedAccessType != null) {
        accessType = inheritedAccessType;
      }
    }

    return accessType;
  }

  /**
   * Get the inherited accessor type of the given class, or null if none is found.
   *
   * @param declaration The inherited accessor type.
   * @return The inherited accessor type of the given class, or null if none is found.
   */
  protected XmlAccessType getInheritedAccessType(ClassDeclaration declaration) {
    ClassType superclass = declaration.getSuperclass();
    if (superclass != null) {
      ClassDeclaration superDeclaration = superclass.getDeclaration();
      if ((superDeclaration != null) && (!Object.class.getName().equals(superDeclaration.getQualifiedName()))) {
        XmlAccessorType xmlAccessorType = superDeclaration.getAnnotation(XmlAccessorType.class);
        if (xmlAccessorType != null) {
          return xmlAccessorType.value();
        }
        else {
          return getInheritedAccessType(superDeclaration);
        }
      }
    }

    return null;
  }

  /**
   * The property order of this xml type.
   *
   * @return The property order of this xml type.
   */
  public String[] getPropertyOrder() {
    String[] propertyOrder = null;

    if (xmlType != null) {
      String[] propOrder = xmlType.propOrder();
      if ((propOrder != null) && (propOrder.length > 0) && ((propOrder.length > 1) || !("".equals(propOrder[0])))) {
        propertyOrder = propOrder;
      }
    }

    return propertyOrder;
  }

  /**
   * The default accessor order of the beans in this package.
   *
   * @return The default accessor order of the beans in this package.
   */
  public XmlAccessOrder getAccessorOrder() {
    XmlAccessOrder order = getPackage().getAccessorOrder();

    XmlAccessorOrder xmlAccessorOrder = getAnnotation(XmlAccessorOrder.class);
    if (xmlAccessorOrder != null) {
      order = xmlAccessorOrder.value();
    }

    return order;
  }

  /**
   * @return The list of class names that this type definition wants you to "see also".
   */
  public Collection<TypeMirror> getSeeAlsos() {
    Collection<TypeMirror> seeAlsos = null;
    XmlSeeAlso seeAlsoInfo = getAnnotation(XmlSeeAlso.class);
    if (seeAlsoInfo != null) {
      seeAlsos = new ArrayList<TypeMirror>();
      try {
        AnnotationProcessorEnvironment env = Context.getCurrentEnvironment();
        for (Class clazz : seeAlsoInfo.value()) {
          TypeDeclaration typeDeclaration = env.getTypeDeclaration(clazz.getName());
          DeclaredType undecorated = env.getTypeUtils().getDeclaredType(typeDeclaration);
          seeAlsos.add(undecorated);
        }
      }
      catch (MirroredTypesException e) {
        seeAlsos.addAll(e.getTypeMirrors());
      }
    }
    return seeAlsos;
  }

  /**
   * Whether this type definition has an "anyAttribute" definition.
   *
   * @return Whether this type definition has an "anyAttribute" definition.
   */
  public boolean isHasAnyAttribute() {
    return hasAnyAttribute;
  }

  /**
   * The enum type containing the known qnames for attributes of the 'any' attribute definition. <code>null</code> if none.
   *
   * @return The enum type containing the known qnames for attributes of the 'any' attribute definition. <code>null</code> if none.
   */
  public TypeMirror getAnyAttributeQNameEnumRef() {
    return anyAttributeQNameEnumRef;
  }

  /**
   * The "anyElement" element.
   *
   * @return The "anyElement" element.
   */
  public AnyElement getAnyElement() {
    return anyElement;
  }

  /**
   * The elements of this type definition.
   *
   * @return The elements of this type definition.
   */
  public SortedSet<Element> getElements() {
    return elements;
  }

  /**
   * The attributes of this type definition.
   *
   * @return The attributes of this type definition.
   */
  public Collection<Attribute> getAttributes() {
    return attributes;
  }

  /**
   * The value of this type definition.
   *
   * @return The value of this type definition.
   */
  public Value getValue() {
    return xmlValue;
  }

  /**
   * The accessor that is the xml id of this type definition, or null if none.
   *
   * @return The accessor that is the xml id of this type definition, or null if none.
   */
  public Accessor getXmlID() {
    return xmlID;
  }

  /**
   * Whether a declaration is xml transient.
   *
   * @param declaration The declaration on which to determine xml transience.
   * @return Whether a declaration is xml transient.
   */
  protected boolean isXmlTransient(Declaration declaration) {
    return (declaration.getAnnotation(XmlTransient.class) != null);
  }

  /**
   * Whether this xml type is anonymous.
   *
   * @return Whether this xml type is anonymous.
   */
  public boolean isAnonymous() {
    return getName() == null;
  }

  /**
   * The schema for this complex type.
   *
   * @return The schema for this complex type.
   */
  public Schema getSchema() {
    return schema;
  }

  // Inherited.
  @Override
  public Schema getPackage() {
    return getSchema();
  }

  /**
   * Whether this is a complex type.
   *
   * @return Whether this is a complex type.
   */
  public boolean isComplex() {
    return false;
  }

  /**
   * Whether this is a enum type.
   *
   * @return Whether this is a enum type.
   */
  public boolean isEnum() {
    return false;
  }

  /**
   * Whether this is a simple type.
   *
   * @return Whether this is a simple type.
   */
  public boolean isSimple() {
    return false;
  }

  /**
   * Whether this type definition is a base object (i.e. a root of the object hierarchy).
   *
   * @return Whether this type definition is a base object
   */
  public boolean isBaseObject() {
    return true;
  }

  /**
   * Set of (human-readable) locations that this type definition is referenced from.
   *
   * @return The referenced-from list.
   */
  public Set<String> getReferencedFrom() {
    return referencedFrom;
  }

  /**
   * The facets here applicable.
   *
   * @return The facets here applicable.
   */
  public Set<Facet> getFacets() {
    return facets;
  }

  /**
   * Accept a validator.
   *
   *
   * @param validator The validator to accept.
   * @return The validation results.
   */
  public abstract ValidationResult accept(BaseValidator validator);

  /**
   * The base type of this type definition.
   *
   * @return The base type of this type definition.
   */
  public abstract XmlType getBaseType();

}
