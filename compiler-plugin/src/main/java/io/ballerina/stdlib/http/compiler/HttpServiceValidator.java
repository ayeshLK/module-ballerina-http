/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.ballerina.stdlib.http.compiler;

import io.ballerina.compiler.api.symbols.ModuleSymbol;
import io.ballerina.compiler.api.symbols.ServiceDeclarationSymbol;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.api.symbols.TypeDescKind;
import io.ballerina.compiler.api.symbols.TypeReferenceTypeSymbol;
import io.ballerina.compiler.api.symbols.TypeSymbol;
import io.ballerina.compiler.api.symbols.UnionTypeSymbol;
import io.ballerina.compiler.syntax.tree.AnnotationNode;
import io.ballerina.compiler.syntax.tree.FunctionDefinitionNode;
import io.ballerina.compiler.syntax.tree.MappingConstructorExpressionNode;
import io.ballerina.compiler.syntax.tree.MappingFieldNode;
import io.ballerina.compiler.syntax.tree.MetadataNode;
import io.ballerina.compiler.syntax.tree.Node;
import io.ballerina.compiler.syntax.tree.NodeList;
import io.ballerina.compiler.syntax.tree.ServiceDeclarationNode;
import io.ballerina.compiler.syntax.tree.SyntaxKind;
import io.ballerina.compiler.syntax.tree.Token;
import io.ballerina.projects.plugins.AnalysisTask;
import io.ballerina.projects.plugins.SyntaxNodeAnalysisContext;
import io.ballerina.tools.diagnostics.Diagnostic;
import io.ballerina.tools.diagnostics.DiagnosticFactory;
import io.ballerina.tools.diagnostics.DiagnosticInfo;
import io.ballerina.tools.diagnostics.DiagnosticSeverity;

import java.util.List;
import java.util.Optional;

import static io.ballerina.stdlib.http.compiler.Constants.BALLERINA;
import static io.ballerina.stdlib.http.compiler.Constants.COLON;
import static io.ballerina.stdlib.http.compiler.Constants.HTTP;
import static io.ballerina.stdlib.http.compiler.Constants.MEDIA_TYPE_SUBTYPE_PREFIX;
import static io.ballerina.stdlib.http.compiler.Constants.MEDIA_TYPE_SUBTYPE_REGEX;
import static io.ballerina.stdlib.http.compiler.Constants.PLUS;
import static io.ballerina.stdlib.http.compiler.Constants.REMOTE_KEYWORD;
import static io.ballerina.stdlib.http.compiler.Constants.SERVICE_CONFIG_ANNOTATION;
import static io.ballerina.stdlib.http.compiler.Constants.SUFFIX_SEPARATOR_REGEX;
import static io.ballerina.stdlib.http.compiler.Constants.UNNECESSARY_CHARS_REGEX;
import static io.ballerina.stdlib.http.compiler.HttpDiagnosticCodes.HTTP_101;
import static io.ballerina.stdlib.http.compiler.HttpDiagnosticCodes.HTTP_119;
import static io.ballerina.stdlib.http.compiler.HttpDiagnosticCodes.HTTP_120;

/**
 * Validates a Ballerina Http Service.
 */
public class HttpServiceValidator implements AnalysisTask<SyntaxNodeAnalysisContext> {

    @Override
    public void perform(SyntaxNodeAnalysisContext syntaxNodeAnalysisContext) {
        List<Diagnostic> diagnostics = syntaxNodeAnalysisContext.semanticModel().diagnostics();
        boolean erroneousCompilation = diagnostics.stream()
                .anyMatch(d -> DiagnosticSeverity.ERROR.equals(d.diagnosticInfo().severity()));
        if (erroneousCompilation) {
            return;
        }

        ServiceDeclarationNode serviceDeclarationNode = (ServiceDeclarationNode) syntaxNodeAnalysisContext.node();
        Optional<Symbol> serviceSymOptional = syntaxNodeAnalysisContext.semanticModel().symbol(serviceDeclarationNode);
        if (serviceSymOptional.isPresent()) {
            List<TypeSymbol> listenerTypes = ((ServiceDeclarationSymbol) serviceSymOptional.get()).listenerTypes();
            if (listenerTypes.stream().noneMatch(this::isListenerBelongsToHttpModule)) {
                return;
            }
        }

        extractServiceAnnotationAndValidate(syntaxNodeAnalysisContext, serviceDeclarationNode);

        NodeList<Node> members = serviceDeclarationNode.members();
        for (Node member : members) {
            if (member.kind() == SyntaxKind.OBJECT_METHOD_DEFINITION) {
                FunctionDefinitionNode node = (FunctionDefinitionNode) member;
                NodeList<Token> tokens = node.qualifierList();
                if (tokens.isEmpty()) {
                    // Object methods are allowed.
                    continue;
                }
                if (tokens.stream().anyMatch(token -> token.text().equals(REMOTE_KEYWORD))) {
                    reportInvalidFunctionType(syntaxNodeAnalysisContext, node);
                }
            } else if (member.kind() == SyntaxKind.RESOURCE_ACCESSOR_DEFINITION) {
                HttpResourceValidator.validateResource(syntaxNodeAnalysisContext, (FunctionDefinitionNode) member);
            }
        }
    }

    private static void extractServiceAnnotationAndValidate(SyntaxNodeAnalysisContext ctx,
                                                            ServiceDeclarationNode serviceDeclarationNode) {
        Optional<MetadataNode> metadataNodeOptional = serviceDeclarationNode.metadata();

        if (metadataNodeOptional.isEmpty()) {
            return;
        }
        NodeList<AnnotationNode> annotations = metadataNodeOptional.get().annotations();
        for (AnnotationNode annotation : annotations) {
            Node annotReference = annotation.annotReference();
            String annotName = annotReference.toString();
            Optional<MappingConstructorExpressionNode> annotValue = annotation.annotValue();
            if (annotReference.kind() != SyntaxKind.QUALIFIED_NAME_REFERENCE) {
                continue;
            }
            String[] annotStrings = annotName.split(COLON);
            if (SERVICE_CONFIG_ANNOTATION.equals(annotStrings[annotStrings.length - 1].trim())
                    && (annotValue.isPresent())) {
                validateServiceConfigAnnotation(ctx, annotValue);
            }
        }
    }

    private static void validateServiceConfigAnnotation(SyntaxNodeAnalysisContext ctx,
                                                            Optional<MappingConstructorExpressionNode> maps) {
        MappingConstructorExpressionNode mapping = maps.get();
        for (MappingFieldNode field : mapping.fields()) {
            String fieldName = field.toString();
            fieldName = fieldName.trim().replaceAll(UNNECESSARY_CHARS_REGEX, "");
            if (field.kind() == SyntaxKind.SPECIFIC_FIELD) {
                String[] strings = fieldName.split(COLON, 2);
                if (MEDIA_TYPE_SUBTYPE_PREFIX.equals(strings[0].trim())) {
                    if (!(strings[1].trim().matches(MEDIA_TYPE_SUBTYPE_REGEX))) {
                        reportInvalidMediaTypeSubtype(ctx, strings[1].trim(), field);
                        break;
                    }
                    if (strings[1].trim().contains(PLUS)) {
                        String suffix = strings[1].trim().split(SUFFIX_SEPARATOR_REGEX, 2)[1];
                        reportErrorMediaTypeSuffix(ctx, suffix.trim(), field);
                        break;
                    }
                }
            }
        }
    }

    private boolean isListenerBelongsToHttpModule(TypeSymbol listenerType) {
        if (listenerType.typeKind() == TypeDescKind.UNION) {
            return ((UnionTypeSymbol) listenerType).memberTypeDescriptors().stream()
                    .filter(typeDescriptor -> typeDescriptor instanceof TypeReferenceTypeSymbol)
                    .map(typeReferenceTypeSymbol -> (TypeReferenceTypeSymbol) typeReferenceTypeSymbol)
                    .anyMatch(typeReferenceTypeSymbol -> isHttpModule(typeReferenceTypeSymbol.getModule().get()));
        }

        if (listenerType.typeKind() == TypeDescKind.TYPE_REFERENCE) {
            return isHttpModule(((TypeReferenceTypeSymbol) listenerType).typeDescriptor().getModule().get());
        }
        return false;
    }

    private boolean isHttpModule(ModuleSymbol moduleSymbol) {
        return HTTP.equals(moduleSymbol.getName().get()) && BALLERINA.equals(moduleSymbol.id().orgName());
    }

    private void reportInvalidFunctionType(SyntaxNodeAnalysisContext ctx, FunctionDefinitionNode node) {
        DiagnosticInfo diagnosticInfo = new DiagnosticInfo(HTTP_101.getCode(), HTTP_101.getMessage(),
                                                           HTTP_101.getSeverity());
        ctx.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo, node.location()));
    }

    private static void reportInvalidMediaTypeSubtype(SyntaxNodeAnalysisContext ctx, String arg,
                                                            MappingFieldNode node) {
        DiagnosticInfo diagnosticInfo = new DiagnosticInfo(HTTP_120.getCode(), String.format(HTTP_120.getMessage(),
                arg), HTTP_120.getSeverity());
        ctx.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo, node.location()));
    }

    private static void reportErrorMediaTypeSuffix(SyntaxNodeAnalysisContext ctx, String suffix,
                                                            MappingFieldNode node) {
        DiagnosticInfo diagnosticInfo = new DiagnosticInfo(HTTP_119.getCode(), String.format(HTTP_119.getMessage(),
                suffix), HTTP_119.getSeverity());
        ctx.reportDiagnostic(DiagnosticFactory.createDiagnostic(diagnosticInfo, node.location()));
    }
}
