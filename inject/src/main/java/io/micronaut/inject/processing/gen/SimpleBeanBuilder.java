package io.micronaut.inject.processing.gen;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Executable;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.ast.FieldElement;
import io.micronaut.inject.ast.MemberElement;
import io.micronaut.inject.ast.MethodElement;
import io.micronaut.inject.ast.PropertyElement;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.inject.writer.BeanDefinitionVisitor;
import io.micronaut.inject.writer.BeanDefinitionWriter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class SimpleBeanBuilder extends AbstractBeanBuilder {

    private final AtomicInteger adaptedMethodIndex = new AtomicInteger(0);
    protected final boolean isAopProxy;
    protected BeanDefinitionVisitor aopProxyVisitor;

    protected SimpleBeanBuilder(ClassElement classElement, VisitorContext visitorContext, boolean isAopProxy) {
        super(classElement, visitorContext);
        this.isAopProxy = isAopProxy;
    }

    @Override
    public final void build() {
        BeanDefinitionVisitor beanDefinitionVisitor = createBeanDefinitionVisitor();
        if (isAopProxy) {
            // Always create AOP proxy
            getAroundAopProxyVisitor(beanDefinitionVisitor, null);
        }
        build(beanDefinitionVisitor);
    }

    @NonNull
    protected BeanDefinitionVisitor createBeanDefinitionVisitor() {
        BeanDefinitionVisitor beanDefinitionWriter = new BeanDefinitionWriter(classElement, metadataBuilder, visitorContext);
        beanDefinitionWriters.add(beanDefinitionWriter);
        beanDefinitionWriter.visitTypeArguments(classElement.getAllTypeArguments());
        visitAnnotationMetadata(beanDefinitionWriter, classElement.getAnnotationMetadata());
        MethodElement constructorElement = classElement.getPrimaryConstructor().orElse(null);
        if (constructorElement != null) {
            beanDefinitionWriter.visitBeanDefinitionConstructor(constructorElement, constructorElement.isPrivate(), visitorContext);
        } else {
            beanDefinitionWriter.visitDefaultConstructor(AnnotationMetadata.EMPTY_METADATA, visitorContext);
        }
        return beanDefinitionWriter;
    }

    protected BeanDefinitionVisitor getAroundAopProxyVisitor(BeanDefinitionVisitor visitor, @Nullable MethodElement methodElement) {
        if (aopProxyVisitor == null) {
            if (classElement.isFinal()) {
                throw new ProcessingException(classElement, "Cannot apply AOP advice to final class. Class must be made non-final to support proxying: " + classElement.getName());
            }

            aopProxyVisitor = aopHelper.createAroundAopProxyWriter(
                visitor,
                isAopProxy || methodElement == null ? classElement.getAnnotationMetadata() : methodElement.getAnnotationMetadata(),
                metadataBuilder,
                visitorContext,
                false
            );
            beanDefinitionWriters.add(aopProxyVisitor);
            MethodElement constructorElement = classElement.getPrimaryConstructor().orElse(null);
            if (constructorElement != null) {
                aopProxyVisitor.visitBeanDefinitionConstructor(
                    constructorElement,
                    constructorElement.isPrivate(),
                    visitorContext
                );
            } else {
                aopProxyVisitor.visitDefaultConstructor(
                    AnnotationMetadata.EMPTY_METADATA,
                    visitorContext
                );
            }
            aopProxyVisitor.visitSuperBeanDefinition(visitor.getBeanDefinitionName());
        }
        return aopProxyVisitor;
    }

    protected boolean processAsProperties() {
        return false;
    }

    protected void build(BeanDefinitionVisitor visitor) {
        ElementQuery<FieldElement> fieldsQuery = ElementQuery.ALL_FIELDS.includeHiddenElements();
        ElementQuery<MethodElement> membersQuery = ElementQuery.ALL_METHODS;
        boolean processAsProperties = processAsProperties();
        if (processAsProperties) {
            fieldsQuery = fieldsQuery.excludePropertyElements();
            membersQuery = membersQuery.excludePropertyElements();
            for (PropertyElement propertyElement : classElement.getBeanProperties()) {
                visitPropertyInternal(visitor, propertyElement);
            }
        }
        List<FieldElement> fields = classElement.getEnclosedElements(fieldsQuery);
        List<FieldElement> declaredFields = new ArrayList<>(fields.size());
        // Process subtype fields first
        for (FieldElement fieldElement : fields) {
            if (fieldElement.getDeclaringType().equals(classElement)) {
                declaredFields.add(fieldElement);
            } else {
                visitFieldInternal(visitor, fieldElement);
            }
        }
        List<MethodElement> methods = classElement.getEnclosedElements(membersQuery);
        List<MethodElement> declaredMethods = new ArrayList<>(methods.size());
        // Process subtype methods first
        for (MethodElement methodElement : methods) {
            if (methodElement.getDeclaringType().equals(classElement)) {
                declaredMethods.add(methodElement);
            } else {
                visitMethodInternal(visitor, methodElement);
            }
        }
        for (FieldElement fieldElement : declaredFields) {
            visitFieldInternal(visitor, fieldElement);
        }
        for (MethodElement methodElement : declaredMethods) {
            visitMethodInternal(visitor, methodElement);
        }
    }

    private void visitFieldInternal(BeanDefinitionVisitor visitor, FieldElement fieldElement) {
        boolean claimed = visitField(visitor, fieldElement);
        if (claimed) {
            addOriginatingElementIfNecessary(visitor, fieldElement);
        }
    }

    private void visitMethodInternal(BeanDefinitionVisitor visitor, MethodElement methodElement) {
        adjustMethodToIncludeClassMetadata(visitor, methodElement);
        if (methodElement.hasAnnotation(ANN_REQUIRES_VALIDATION)) {
            methodElement.annotate(ANN_VALIDATED);
        }
        boolean claimed = visitMethod(visitor, methodElement);
        if (claimed) {
            addOriginatingElementIfNecessary(visitor, methodElement);
        }
    }

    private void visitPropertyInternal(BeanDefinitionVisitor visitor, PropertyElement propertyElement) {
        propertyElement.getWriteMethod().ifPresent(methodElement -> {
            if (methodElement.hasAnnotation(ANN_REQUIRES_VALIDATION)) {
                methodElement.annotate(ANN_VALIDATED);
            }
        });
        propertyElement.getReadMethod().ifPresent(methodElement -> {
            if (methodElement.hasAnnotation(ANN_REQUIRES_VALIDATION)) {
                methodElement.annotate(ANN_VALIDATED);
            }
        });
        boolean claimed = visitProperty(visitor, propertyElement);
        if (claimed) {
            propertyElement.getReadMethod().ifPresent(element -> addOriginatingElementIfNecessary(visitor, element));
            propertyElement.getWriteMethod().ifPresent(element -> addOriginatingElementIfNecessary(visitor, element));
            propertyElement.getField().ifPresent(element -> addOriginatingElementIfNecessary(visitor, element));
        }
    }

    protected boolean visitProperty(BeanDefinitionVisitor visitor, PropertyElement propertyElement) {
        return false;
    }

    protected boolean visitMethod(BeanDefinitionVisitor visitor, MethodElement methodElement) {
        // All the cases above are using executable methods
        boolean claimed = false;
        if (methodElement.hasDeclaredAnnotation(AnnotationUtil.POST_CONSTRUCT)) {
            staticMethodCheck(methodElement);
            // TODO: Require @ReflectiveAccess for private methods in Micronaut 4
            visitor.visitPostConstructMethod(
                methodElement.getDeclaringType(),
                methodElement,
                methodElement.isReflectionRequired(classElement),
                visitorContext);
            claimed = true;
        }
        if (methodElement.hasDeclaredAnnotation(AnnotationUtil.PRE_DESTROY)) {
            staticMethodCheck(methodElement);
            // TODO: Require @ReflectiveAccess for private methods in Micronaut 4
            visitor.visitPreDestroyMethod(
                methodElement.getDeclaringType(),
                methodElement,
                methodElement.isReflectionRequired(classElement),
                visitorContext
            );
            claimed = true;
        }
        if (claimed) {
            return true;
        }
        if (!methodElement.isStatic() && isInjectPointMethod(methodElement)) {
            staticMethodCheck(methodElement);
            // TODO: Require @ReflectiveAccess for private methods in Micronaut 4
            visitor.visitMethodInjectionPoint(
                methodElement.getDeclaringType(),
                methodElement,
                methodElement.isReflectionRequired(classElement),
                visitorContext
            );
            return true;
        }
        if (methodElement.isStatic() && isExplicitlyAnnotatedAsExecutable(methodElement)) {
            // Only allow static executable methods when it's explicitly annotated with Executable.class
            return false;
        }
        // This method requires pre-processing. See Executable#processOnStartup()
        boolean preprocess = methodElement.isTrue(Executable.class, "processOnStartup");
        if (preprocess) {
            visitor.setRequiresMethodProcessing(true);
        }
        if (methodElement.hasStereotype("io.micronaut.aop.Adapter")) {
            staticMethodCheck(methodElement);
            visitAdaptedMethod(visitor, methodElement);
            return true;
        }
        if (visitAopMethod(visitor, methodElement)) {
            return true;
        }
        return visitExecutableMethod(visitor, methodElement);
    }

    protected boolean visitAopMethod(BeanDefinitionVisitor visitor, MethodElement methodElement) {
        boolean aopDefinedOnClassAndPublicMethod = isAopProxy && (methodElement.isPublic() || methodElement.isPackagePrivate());
        AnnotationMetadata methodAnnotationMetadata = getElementAnnotationMetadata(methodElement);
        if (aopDefinedOnClassAndPublicMethod ||
            !isAopProxy && hasAroundStereotype(methodAnnotationMetadata) ||
            hasDeclaredAroundAdvice(methodAnnotationMetadata) && !classElement.isAbstract()) {
            if (methodElement.isFinal()) {
                if (hasDeclaredAroundAdvice(methodAnnotationMetadata)) {
                    throw new ProcessingException(methodElement, "Method defines AOP advice but is declared final. Change the method to be non-final in order for AOP advice to be applied.");
                } else if (aopDefinedOnClassAndPublicMethod && isDeclaredInThisClass(methodElement)) {
                    throw new ProcessingException(methodElement, "Public method inherits AOP advice but is declared final. Either make the method non-public or apply AOP advice only to public methods declared on the class.");
                }
                return false;
            } else if (methodElement.isPrivate()) {
                throw new ProcessingException(methodElement, "Method annotated as executable but is declared private. Change the method to be non-private in order for AOP advice to be applied.");
            } else if (methodElement.isStatic()) {
                throw new ProcessingException(methodElement, "Method defines AOP advice but is declared static");
            }
            BeanDefinitionVisitor aopProxyVisitor = getAroundAopProxyVisitor(visitor, methodElement);
            aopHelper.visitAroundMethod(aopProxyVisitor, classElement, methodElement);
            return true;
        }
        return false;
    }

    protected boolean isInjectPointMethod(MethodElement methodElement) {
        return methodElement.hasDeclaredStereotype(AnnotationUtil.INJECT);
    }

    private void staticMethodCheck(MethodElement methodElement) {
        if (methodElement.isStatic()) {
            if (!isExplicitlyAnnotatedAsExecutable(methodElement)) {
                throw new ProcessingException(methodElement, "Static methods only allowed when annotated with @Executable");
            }
            failIfMethodNotAccessible(methodElement);
        }
    }

    private void failIfMethodNotAccessible(MethodElement methodElement) {
        if (!methodElement.isAccessible(classElement)) {
            throw new ProcessingException(methodElement, "Method is not accessible for the invocation. To invoke the method using reflection annotate it with @ReflectiveAccess");
        }
    }

    private static boolean isExplicitlyAnnotatedAsExecutable(MethodElement methodElement) {
        return !getElementAnnotationMetadata(methodElement).hasDeclaredAnnotation(Executable.class);
    }

    protected boolean visitField(BeanDefinitionVisitor visitor, FieldElement fieldElement) {
        if (fieldElement.isStatic() || fieldElement.isFinal()) {
            return false;
        }
        AnnotationMetadata fieldAnnotationMetadata = fieldElement.getAnnotationMetadata();
        if (fieldAnnotationMetadata.hasStereotype(Value.class) || fieldAnnotationMetadata.hasStereotype(Property.class)) {
            visitor.visitFieldValue(fieldElement.getDeclaringType(), fieldElement, fieldElement.getAnnotationMetadata(), isOptionalFieldValue(), fieldElement.isReflectionRequired(classElement));
            return true;
        }
        if (fieldAnnotationMetadata.hasStereotype(AnnotationUtil.INJECT)
            || (fieldAnnotationMetadata.hasDeclaredStereotype(AnnotationUtil.QUALIFIER) && !fieldAnnotationMetadata.hasDeclaredAnnotation(Bean.class))) {
            visitor.visitFieldInjectionPoint(fieldElement.getDeclaringType(), fieldElement, fieldElement.isReflectionRequired(classElement));
            return true;
        }
        return false;
    }

    protected boolean isOptionalFieldValue() {
        return false;
    }

    protected void addOriginatingElementIfNecessary(BeanDefinitionVisitor writer, MemberElement memberElement) {
        if (!isDeclaredInThisClass(memberElement)) {
            writer.addOriginatingElement(memberElement.getDeclaringType());
        }
    }

    protected boolean visitExecutableMethod(BeanDefinitionVisitor visitor, MethodElement methodElement) {
        if (!methodElement.hasStereotype(Executable.class)) {
            return false;
        }
        if (getElementAnnotationMetadata(methodElement).hasStereotype(Executable.class)) {
            // @Executable annotated on the method
            // Throw error if it cannot be accessed without the reflection
            if (!methodElement.isAccessible()) {
                throw new ProcessingException(methodElement, "Method annotated as executable but is declared private. To invoke the method using reflection annotate it with @ReflectiveAccess");
            }
        } else if (!isDeclaredInThisClass(methodElement) && !methodElement.getDeclaringType().hasStereotype(Executable.class)) {
            // @Executable not annotated on the declared class or method
            // Only include public methods
            if (!methodElement.isPublic()) {
                return false;
            }
        }
        // else
        // @Executable annotated on the class
        // only include own accessible methods or the ones annotated with @ReflectiveAccess
        if (methodElement.isAccessible()
            || !methodElement.isPrivate() && methodElement.getClass().getSimpleName().contains("Groovy")) {
            visitor.visitExecutableMethod(classElement, methodElement, visitorContext);
        }
        return true;
    }

    protected boolean isDeclaredInThisClass(MemberElement memberElement) {
        return classElement.getName().equals(memberElement.getDeclaringType().getName());
    }

    private void visitAdaptedMethod(BeanDefinitionVisitor visitor, MethodElement sourceMethod) {
        BeanDefinitionVisitor adapter = aopHelper
            .visitAdaptedMethod(classElement, sourceMethod, metadataBuilder, adaptedMethodIndex, visitorContext);
        if (adapter != null) {
            visitor.visitExecutableMethod(sourceMethod.getDeclaringType(), sourceMethod, visitorContext);
            beanDefinitionWriters.add(adapter);
        }
    }

}