package io.smallrye.graphql.schema.creator;

import java.lang.reflect.Modifier;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.jboss.jandex.*;

import io.smallrye.graphql.schema.Annotations;
import io.smallrye.graphql.schema.SchemaBuilderException;
import io.smallrye.graphql.schema.helper.DefaultValueHelper;
import io.smallrye.graphql.schema.helper.DescriptionHelper;
import io.smallrye.graphql.schema.helper.Direction;
import io.smallrye.graphql.schema.helper.FormatHelper;
import io.smallrye.graphql.schema.helper.MappingHelper;
import io.smallrye.graphql.schema.helper.MethodHelper;
import io.smallrye.graphql.schema.helper.NonNullHelper;
import io.smallrye.graphql.schema.model.Argument;
import io.smallrye.graphql.schema.model.Operation;
import io.smallrye.graphql.schema.model.OperationType;
import io.smallrye.graphql.schema.model.Reference;

/**
 * Creates a Operation object
 * 
 * @author Phillip Kruger (phillip.kruger@redhat.com)
 */
public class OperationCreator {

    private final ReferenceCreator referenceCreator;
    private final ArgumentCreator argumentCreator;

    public OperationCreator(ReferenceCreator referenceCreator, ArgumentCreator argumentCreator) {
        this.referenceCreator = referenceCreator;
        this.argumentCreator = argumentCreator;
    }

    /**
     * This creates a single operation.
     * It translate to one entry under a query / mutation in the schema or
     * one method in the Java class annotated with Query or Mutation
     * 
     * @param methodInfo the java method
     * @param operationType the type of operation (Query / Mutation)
     * @param type
     * @return a Operation that defines this GraphQL Operation
     */
    public Operation createOperation(MethodInfo methodInfo, List<ClassInfo> hierarchy, OperationType operationType,
            final io.smallrye.graphql.schema.model.Type type) {

        if (!Modifier.isPublic(methodInfo.flags())) {
            throw new IllegalArgumentException(
                    "Method " + methodInfo.declaringClass().name().toString() + "#" + methodInfo.name()
                            + " is used as an operation, but is not public");
        }

        if (hierarchy.isEmpty()) {
            hierarchy = Collections.singletonList(methodInfo.declaringClass());
        }

        Map<Type, Type> typeMap = buildTypeMap(hierarchy);

        Annotations annotationsForMethod = Annotations.getAnnotationsForMethod(methodInfo);
        Type fieldType = resolveTypeParameter(typeMap, methodInfo.returnType());

        // Name
        String name = getOperationName(methodInfo, operationType, annotationsForMethod);

        // Description
        Optional<String> maybeDescription = DescriptionHelper.getDescriptionForField(annotationsForMethod, fieldType);

        // Field Type
        validateFieldType(methodInfo, operationType);
        Reference reference = referenceCreator.createReferenceForOperationField(fieldType, annotationsForMethod);

        Operation operation = new Operation(hierarchy.get(0).name().toString(),
                methodInfo.name(),
                MethodHelper.getPropertyName(Direction.OUT, methodInfo.name()),
                name,
                maybeDescription.orElse(null),
                reference,
                operationType);
        if (type != null) {
            operation.setSourceFieldOn(new Reference(type));
        }

        // NotNull
        if (NonNullHelper.markAsNonNull(fieldType, annotationsForMethod)) {
            operation.setNotNull(true);
        }

        // Wrapper
        operation.setWrapper(WrapperCreator.createWrapper(fieldType).orElse(null));

        // TransformInfo
        operation.setTransformation(FormatHelper.getFormat(fieldType, annotationsForMethod).orElse(null));

        // MappingInfo
        operation.setMapping(MappingHelper.getMapping(operation, annotationsForMethod).orElse(null));

        // Default Value
        operation.setDefaultValue(DefaultValueHelper.getDefaultValue(annotationsForMethod).orElse(null));

        // Arguments
        List<Type> parameters = resolveTypeParameters(typeMap, methodInfo.parameters());
        for (short i = 0; i < parameters.size(); i++) {
            Optional<Argument> maybeArgument = argumentCreator.createArgument(operation, methodInfo, i);
            maybeArgument.ifPresent(operation::addArgument);
        }

        return operation;
    }

    private Map<Type, Type> buildTypeMap(List<ClassInfo> hierarchy) {
        HashMap<Type, Type> resolved = new HashMap<>();
        if (hierarchy.size() > 0) {
            ClassInfo classInfo = hierarchy.get(hierarchy.size() - 1);
            if (!classInfo.typeParameters().isEmpty() && hierarchy.size() > 1) {
                int idx = hierarchy.size() - 2;
                List<TypeVariable> typeParameters = classInfo.typeParameters();
                Type[] resolvedTypes = new Type[typeParameters.size()];
                Arrays.fill(resolvedTypes, null);
                Consumer<List<Type>> update = (List<Type> args) -> IntStream.range(0, typeParameters.size())
                        .forEach(i -> {
                            if (!args.get(i).equals(typeParameters.get(i))) {
                                resolvedTypes[i] = args.get(i);
                            }
                        });
                while (idx >= 0 && Arrays.asList(resolvedTypes).contains(null)) {
                    ClassInfo ancestor = hierarchy.get(idx);
                    if (ancestor.interfaceNames().contains(classInfo.name())) {
                        ancestor.interfaceTypes().stream()
                                .filter(it -> it.name().equals(classInfo.name()))
                                .map(it -> it.asParameterizedType().arguments())
                                .forEach(update);
                    } else {
                        update.accept(ancestor.superClassType().asParameterizedType().arguments());
                    }

                    idx--;
                }

                IntStream.range(0, typeParameters.size()).forEach(i -> resolved.put(typeParameters.get(i), resolvedTypes[i]));
            }
        }

        return resolved;
    }

    private List<Type> resolveTypeParameters(Map<Type, Type> typeMap, List<Type> types) {
        return types.stream().map(t -> resolveTypeParameter(typeMap, t)).collect(Collectors.toList());
    }

    private Type resolveTypeParameter(Map<Type, Type> typeMap, Type type) {
        if (typeMap.containsKey(type)) {
            return typeMap.get(type);
        } else if (type.kind() == Type.Kind.PARAMETERIZED_TYPE) {
            ParameterizedType parameterizedType = type.asParameterizedType();
            return ParameterizedType.create(
                    parameterizedType.name(),
                    parameterizedType.arguments().stream().map(t -> typeMap.getOrDefault(t, t)).toArray(Type[]::new),
                    parameterizedType.owner());
        }

        return type;
    }

    private static void validateFieldType(MethodInfo methodInfo, OperationType operationType) {
        Type returnType = methodInfo.returnType();
        if (returnType.kind().equals(Type.Kind.VOID)) {
            throw new SchemaBuilderException(
                    "Can not have a void return for [" + operationType.name()
                            + "] on method [" + methodInfo.name() + "]");
        }
    }

    /**
     * Get the name from annotation(s) or default.
     * This is for operations (query, mutation and source)
     * 
     * @param methodInfo the java method
     * @param operationType the type (query, mutation)
     * @param annotations the annotations on this method
     * @return the operation name
     */
    private static String getOperationName(MethodInfo methodInfo, OperationType operationType, Annotations annotations) {
        DotName operationAnnotation = getOperationAnnotation(operationType);

        // If the @Query or @Mutation annotation has a value, use that, else use name or jsonb property
        return annotations.getOneOfTheseMethodAnnotationsValue(
                operationAnnotation,
                Annotations.NAME,
                Annotations.JSONB_PROPERTY)
                .orElse(getDefaultExecutionTypeName(methodInfo, operationType));

    }

    private static DotName getOperationAnnotation(OperationType operationType) {
        if (operationType.equals(OperationType.QUERY)) {
            return Annotations.QUERY;
        } else if (operationType.equals(OperationType.MUTATION)) {
            return Annotations.MUTATION;
        }
        return null;
    }

    private static String getDefaultExecutionTypeName(MethodInfo methodInfo, OperationType operationType) {
        String methodName = methodInfo.name();
        if (operationType.equals(OperationType.QUERY)) {
            methodName = MethodHelper.getPropertyName(Direction.OUT, methodName);
        } else if (operationType.equals(OperationType.MUTATION)) {
            methodName = MethodHelper.getPropertyName(Direction.IN, methodName);
        }
        return methodName;
    }

}
