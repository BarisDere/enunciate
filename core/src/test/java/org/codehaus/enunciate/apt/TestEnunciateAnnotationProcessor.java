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

package org.codehaus.enunciate.apt;

import com.sun.mirror.declaration.ClassDeclaration;
import com.sun.mirror.declaration.TypeDeclaration;
import com.sun.mirror.apt.Messager;
import junit.framework.AssertionFailedError;
import junit.framework.Test;
import org.codehaus.enunciate.EnunciateException;
import org.codehaus.enunciate.InAPTTestCase;
import org.codehaus.enunciate.OutsideAPTOkay;
import org.codehaus.enunciate.config.EnunciateConfiguration;
import org.codehaus.enunciate.contract.jaxb.*;
import org.codehaus.enunciate.contract.jaxws.EndpointInterface;
import org.codehaus.enunciate.contract.validation.BaseValidator;
import org.codehaus.enunciate.contract.validation.ValidationResult;
import org.codehaus.enunciate.contract.validation.Validator;
import org.codehaus.enunciate.main.Enunciate;
import org.codehaus.enunciate.modules.BasicDeploymentModule;
import org.codehaus.enunciate.modules.DeploymentModule;
import org.easymock.EasyMock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/**
 * @author Ryan Heaton
 */
public class TestEnunciateAnnotationProcessor extends InAPTTestCase {

  /**
   * Tests the process.
   */
  @OutsideAPTOkay
  public void testProcess() throws Exception {
    final MockDeploymentModule mockModule = new MockDeploymentModule();
    EnunciateConfiguration config = new EnunciateConfiguration() {
      @Override
      public List<DeploymentModule> getEnabledModules() {
        return Arrays.asList(new DeploymentModule[]{mockModule});
      }
    };
    Enunciate enunciate = new Enunciate(new String[0]);
    enunciate.setConfig(config);

    EnunciateAnnotationProcessor processor = new EnunciateAnnotationProcessor(enunciate) {
      @Override
      protected EnunciateFreemarkerModel getRootModel() {
        mockModule.model = new EnunciateFreemarkerModel();
        return null;
      }
    };

    processor.process();
    processor.throwAnyErrors();
    assertTrue(mockModule.generated);
    assertNotNull(mockModule.model);
  }

  /**
   * Tests getting the root model.
   */
  public void testGetRootModel() throws Exception {
    EnunciateConfiguration config = new EnunciateConfiguration();
    config.putNamespace("urn:test", "test");
    config.putNamespace("http://enunciate.codehaus.org/samples/contract", "tContract");
    config.setExcludeUnreferencedClasses(false);

    final boolean[] validated = new boolean[]{false};
    Enunciate enunciate = new Enunciate(new String[0]);
    enunciate.setConfig(config);
    EnunciateAnnotationProcessor processor = new EnunciateAnnotationProcessor(enunciate) {
      @Override
      protected void validate(EnunciateFreemarkerModel model) {
        validated[0] = true;
      }

      @Override
      public boolean isEndpointInterface(TypeDeclaration declaration) {
        return "org.codehaus.enunciate.samples.services.NamespacedWebService".equals(declaration.getQualifiedName());
      }

      @Override
      protected boolean isPotentialXmlSchemaType(TypeDeclaration declaration) {
        boolean potentialSchemaType = "org.codehaus.enunciate.samples.schema".equals(declaration.getPackage().getQualifiedName());
        String simpleName = declaration.getSimpleName();
        potentialSchemaType &= (simpleName.equals("BeanOne") || simpleName.equals("BeanTwo") || simpleName.equals("BeanThree"));
        return potentialSchemaType;
      }

      @Override
      public TypeDefinition createTypeDefinition(ClassDeclaration declaration, EnunciateFreemarkerModel model) {
        if ("org.codehaus.enunciate.samples.schema.BeanOne".equals(declaration.getQualifiedName())) {
          return new ComplexTypeDefinition(declaration);
        }
        else if ("org.codehaus.enunciate.samples.schema.BeanTwo".equals(declaration.getQualifiedName())) {
          return new ComplexTypeDefinition(declaration);
        }
        else if ("org.codehaus.enunciate.samples.schema.BeanThree".equals(declaration.getQualifiedName())) {
          return new ComplexTypeDefinition(declaration);
        }

        throw new AssertionFailedError(declaration.getQualifiedName() + " shouldn't have been considered as a potential schema type.");
      }

      @Override
      public RootElementDeclaration createRootElementDeclaration(ClassDeclaration declaration, TypeDefinition typeDefinition) {
        if ("org.codehaus.enunciate.samples.schema.BeanThree".equals(declaration.getQualifiedName())) {
          return new RootElementDeclaration(declaration, typeDefinition);
        }

        return null;
      }
    };

    EnunciateFreemarkerModel model = processor.getRootModel();
    assertEquals("One and only one endpoint interface should have been found.", 1, model.endpointInterfaces.size());
    assertEquals("Three and only three type definitions should have been found.", 3, model.typeDefinitions.size());
    assertEquals("One and only one root elements should have been found.", 1, model.rootElements.size());
    assertNotNull(model.findTypeDefinition((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.schema.BeanOne")));
    assertNotNull(model.findTypeDefinition((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.schema.BeanTwo")));
    assertNotNull(model.findTypeDefinition((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.schema.BeanThree")));
    assertNotNull(model.findRootElementDeclaration((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.schema.BeanThree")));
    assertNotNull(model.findJsonRootElementDeclaration((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.json.Person")));
    assertNotNull(model.findJsonTypeDefinition((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.json.Person")));
    assertNotNull(model.findJsonTypeDefinition((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.json.PersonType")));
    assertNotNull(model.findJsonTypeDefinition((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.json.Name")));
    assertNotNull(model.findJsonTypeDefinition((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.json.Address")));
    assertTrue(validated[0]);
    assertEquals("tContract", model.getNamespacesToPrefixes().get("http://enunciate.codehaus.org/samples/contract"));
    assertEquals("test", model.getNamespacesToPrefixes().get("urn:test"));

    //todo: test that the REST endpoints are corrected added to the model.
  }

  /**
   * Tests getting the root model.
   */
  public void testGetRootModelExcludingUnreferenced() throws Exception {
    EnunciateConfiguration config = new EnunciateConfiguration();
    config.putNamespace("urn:test", "test");
    config.putNamespace("http://enunciate.codehaus.org/samples/contract", "tContract");

    final boolean[] validated = new boolean[]{false};
    Enunciate enunciate = new Enunciate(new String[0]);
    enunciate.setConfig(config);
    EnunciateAnnotationProcessor processor = new EnunciateAnnotationProcessor(enunciate) {
      @Override
      protected void validate(EnunciateFreemarkerModel model) {
        validated[0] = true;
      }

      @Override
      public boolean isEndpointInterface(TypeDeclaration declaration) {
        return "org.codehaus.enunciate.samples.services.NamespacedWebService".equals(declaration.getQualifiedName());
      }

      @Override
      protected boolean isPotentialXmlSchemaType(TypeDeclaration declaration) {
        boolean potentialSchemaType = "org.codehaus.enunciate.samples.schema".equals(declaration.getPackage().getQualifiedName());
        String simpleName = declaration.getSimpleName();
        potentialSchemaType &= (simpleName.equals("BeanOne") || simpleName.equals("BeanTwo") || simpleName.equals("BeanThree"));
        return potentialSchemaType;
      }

      @Override
      public TypeDefinition createTypeDefinition(ClassDeclaration declaration, EnunciateFreemarkerModel model) {
        if ("org.codehaus.enunciate.samples.schema.BeanOne".equals(declaration.getQualifiedName())) {
          return new ComplexTypeDefinition(declaration);
        }
        else if ("org.codehaus.enunciate.samples.schema.BeanTwo".equals(declaration.getQualifiedName())) {
          return new ComplexTypeDefinition(declaration);
        }
        else if ("org.codehaus.enunciate.samples.schema.BeanThree".equals(declaration.getQualifiedName())) {
          return new ComplexTypeDefinition(declaration);
        }

        throw new AssertionFailedError(declaration.getQualifiedName() + " shouldn't have been considered as a potential schema type.");
      }

      @Override
      public RootElementDeclaration createRootElementDeclaration(ClassDeclaration declaration, TypeDefinition typeDefinition) {
        if ("org.codehaus.enunciate.samples.schema.BeanThree".equals(declaration.getQualifiedName())) {
          return new RootElementDeclaration(declaration, typeDefinition);
        }

        return null;
      }
    };

    EnunciateFreemarkerModel model = processor.getRootModel();
    assertEquals("One and only one endpoint interface should have been found.", 1, model.endpointInterfaces.size());
    assertEquals("One and only one type definitions should have been found (unreferenced types are excluded).", 1, model.typeDefinitions.size());
    assertEquals("One and only one root elements should have been found.", 1, model.rootElements.size());
    assertNull(model.findTypeDefinition((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.schema.BeanOne")));
    assertNull(model.findTypeDefinition((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.schema.BeanTwo")));
    assertNotNull(model.findTypeDefinition((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.schema.BeanThree")));
    assertNotNull(model.findRootElementDeclaration((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.schema.BeanThree")));
    assertNull(model.findJsonRootElementDeclaration((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.json.Person")));
    assertNull(model.findJsonTypeDefinition((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.json.Person")));
    assertNull(model.findJsonTypeDefinition((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.json.PersonType")));
    assertNull(model.findJsonTypeDefinition((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.json.Name")));
    assertNotNull(model.findJsonTypeDefinition((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.json.Address")));

    assertTrue(validated[0]);

    assertEquals("tContract", model.getNamespacesToPrefixes().get("http://enunciate.codehaus.org/samples/contract"));
    assertEquals("test", model.getNamespacesToPrefixes().get("urn:test"));

    //todo: test that the REST endpoints are corrected added to the model.
  }

  /**
   * Tests validating the model.
   */
  public void testValidate() throws Exception {
    final EndpointInterface ei = new EndpointInterface(getDeclaration("org.codehaus.enunciate.samples.services.NamespacedWebService"));
    final ComplexTypeDefinition beanOne = new ComplexTypeDefinition((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.schema.BeanOne"));
    final ComplexTypeDefinition beanTwo = new ComplexTypeDefinition((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.schema.BeanTwo"));
    final ComplexTypeDefinition beanThree = new ComplexTypeDefinition((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.schema.BeanThree"));
    final Messager messager = EasyMock.createNiceMock(Messager.class);
    RootElementDeclaration beanThreeElement = new RootElementDeclaration((ClassDeclaration) getDeclaration("org.codehaus.enunciate.samples.schema.BeanThree"), beanThree);

    EnunciateConfiguration config = new EnunciateConfiguration();
    final MockValidator validator = new MockValidator();
    config.setValidator(validator);
    config.addModule(new BasicDeploymentModule() {
      @Override
      public Validator getValidator() {
        return validator;
      }
    });

    config.addModule(new BasicDeploymentModule() {
      public String getName() {
        return "basic2";
      }

      @Override
      public Validator getValidator() {
        return validator;
      }
    });

    Enunciate enunciate = new Enunciate(new String[0]);
    enunciate.setConfig(config);
    EnunciateAnnotationProcessor processor = new EnunciateAnnotationProcessor(enunciate) {
      @Override
      protected Messager getMessager() {
        return messager;
      }
    };
    EnunciateFreemarkerModel model = new EnunciateFreemarkerModel();
    model.add(ei);
    model.add(beanOne);
    model.add(beanTwo);
    model.add(beanThree);
    model.add(beanThreeElement);

    processor.validate(model);

    for (int i = 0; i < 3; i++) {
      assertTrue("Endpoint interface should have been validated three (and only three) times.", validator.validatedObjects.remove(ei));
    }
    assertFalse("Endpoint interface should have been validated three (and only three) times.", validator.validatedObjects.remove(ei));

    for (int i = 0; i < 3; i++) {
      assertTrue("Bean one should have been validated three (and only three) times.", validator.validatedObjects.remove(beanOne));
    }
    assertFalse("Bean one should have been validated three (and only three) times.", validator.validatedObjects.remove(beanOne));

    for (int i = 0; i < 3; i++) {
      assertTrue("Bean two should have been validated three (and only three) times.", validator.validatedObjects.remove(beanTwo));
    }
    assertFalse("Bean two should have been validated three (and only three) times.", validator.validatedObjects.remove(beanTwo));

    for (int i = 0; i < 3; i++) {
      assertTrue("Bean three should have been validated three (and only three) times.", validator.validatedObjects.remove(beanThree));
    }

    for (int i = 0; i < 3; i++) {
      assertTrue("Bean three element should have been validated three (and only three) times.", validator.validatedObjects.remove(beanThreeElement));
    }

    assertFalse("Bean three should have been validated three (and only three) times.", validator.validatedObjects.remove(beanThree));
    assertFalse("Bean three element should have been validated three (and only three) times.", validator.validatedObjects.remove(beanThreeElement));

    enunciate.setConfig(config);
    processor = new EnunciateAnnotationProcessor(enunciate) {
      @Override
      protected ValidationResult validate(EnunciateFreemarkerModel model, Validator validator) {
        ValidationResult result = new ValidationResult();
        result.addWarning(beanThree, "test warning.");
        result.addError(beanTwo, "test error");
        result.addError(beanTwo, "test error2");
        return result;
      }

      @Override
      protected Messager getMessager() {
        return messager;
      }
    };

    try {
      processor.validate(model); //todo: make sure all warnings and errors are printed.
      fail("Should have thrown a model validation exception.");
    }
    catch (ModelValidationException e) {
      //fall through...
    }
  }

  /**
   * Whether a type is a simple type.
   */
  public void testIsSimpleType() throws Exception {
    EnunciateFreemarkerModel model = new EnunciateFreemarkerModel ();
    assertFalse(model.isSimpleType(getDeclaration("org.codehaus.enunciate.samples.schema.BeanOne")));
    assertFalse(model.isSimpleType(getDeclaration("org.codehaus.enunciate.samples.schema.BeanTwo")));
    assertFalse(model.isSimpleType(getDeclaration("org.codehaus.enunciate.samples.schema.BeanThree")));
    assertFalse(model.isSimpleType(getDeclaration("org.codehaus.enunciate.samples.anotherschema.SimpleTypeComplexContentBean")));
    assertTrue(model.isSimpleType(getDeclaration("org.codehaus.enunciate.samples.anotherschema.SimpleTypeSimpleContentBean")));
  }

  private static class MockValidator extends BaseValidator {
    Collection<Object> validatedObjects = new ArrayList<Object>();

    @Override
    public ValidationResult validateEndpointInterface(EndpointInterface ei) {
      validatedObjects.add(ei);
      return super.validateEndpointInterface(ei);
    }

    @Override
    public ValidationResult validateComplexType(ComplexTypeDefinition complexType) {
      validatedObjects.add(complexType);
      return super.validateComplexType(complexType);
    }

    @Override
    public ValidationResult validateSimpleType(SimpleTypeDefinition simpleType) {
      validatedObjects.add(simpleType);
      return super.validateSimpleType(simpleType);
    }

    @Override
    public ValidationResult validateEnumType(EnumTypeDefinition enumType) {
      validatedObjects.add(enumType);
      return super.validateEnumType(enumType);
    }

    @Override
    public ValidationResult validateRootElement(RootElementDeclaration rootElementDeclaration) {
      validatedObjects.add(rootElementDeclaration);
      return super.validateRootElement(rootElementDeclaration);
    }
  }

  private static class MockDeploymentModule extends BasicDeploymentModule {
    boolean generated = false;

    EnunciateFreemarkerModel model = null;

    @Override
    protected void doGenerate() throws EnunciateException, IOException {
      this.generated = true;
    }
  }

  public static Test suite() {
    return createSuite(TestEnunciateAnnotationProcessor.class);
  }
}
