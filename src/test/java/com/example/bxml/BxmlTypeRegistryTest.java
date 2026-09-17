package com.example.bxml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/**
 * Testes unitários para {@link BxmlTypeRegistry} contra um {@code <TypeInfos>} real (copiado, com
 * os mesmos ids, de {@code examples/BirthdayRegister/bdp/Register.bxml}) — inclui o caso de
 * codomínio-tupla {@code birthday : PERSON +-> (DAY*MONTH*YEAR)} (id 4: {@code
 * POW(INTEGER*(DAY*MONTH*YEAR))}) que exercita {@link TupleCodomainTypeRegistry}.
 */
class BxmlTypeRegistryTest {

    private static final String MACHINE_XML =
            "<Machine xmlns='https://www.atelierb.eu/Formats/bxml' name='Register'>"
                    + "<TypeInfos>"
                    + "<Type id='0'><Id value='BOOL'/></Type>"
                    + "<Type id='1'><Id value='INTEGER'/></Type>"
                    + "<Type id='2'><Unary_Exp op='POW'><Id value='INTEGER'/></Unary_Exp></Type>"
                    + "<Type id='3'><Unary_Exp op='POW'><Unary_Exp op='POW'><Id value='INTEGER'/></Unary_Exp></Unary_Exp></Type>"
                    + "<Type id='4'>"
                    + "<Unary_Exp op='POW'>"
                    + "<Binary_Exp op='*'>"
                    + "<Id value='INTEGER'/>"
                    + "<Binary_Exp op='*'>"
                    + "<Binary_Exp op='*'><Id value='DAY'/><Id value='MONTH'/></Binary_Exp>"
                    + "<Id value='YEAR'/>"
                    + "</Binary_Exp>"
                    + "</Binary_Exp>"
                    + "</Unary_Exp>"
                    + "</Type>"
                    + "</TypeInfos>"
                    + "</Machine>";

    private static Element parseMachine(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // opcional
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc =
                builder.parse(new InputSource(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));
        Element root = doc.getDocumentElement();
        root.normalize();
        return root;
    }

    @Test
    void scalarTyprefsResolveToAcslScalarTypes() throws Exception {
        BxmlTypeRegistry registry = BxmlTypeRegistry.fromMachine(parseMachine(MACHINE_XML));
        assertEquals("boolean", registry.acslVariableLogicTypeFromTypref(0));
        assertEquals("integer", registry.acslVariableLogicTypeFromTypref(1));
    }

    @Test
    void negativeTyprefDefaultsToInteger() throws Exception {
        BxmlTypeRegistry registry = BxmlTypeRegistry.fromMachine(parseMachine(MACHINE_XML));
        assertEquals("integer", registry.acslVariableLogicTypeFromTypref(-1));
    }

    @Test
    void powOfIntegerResolvesToSetOfInteger() throws Exception {
        BxmlTypeRegistry registry = BxmlTypeRegistry.fromMachine(parseMachine(MACHINE_XML));
        assertEquals("Set<integer>", registry.acslVariableLogicTypeFromTypref(2));
    }

    @Test
    void nestedPowSpacesTheClosingAngleBracketToAvoidShiftTokenization() throws Exception {
        BxmlTypeRegistry registry = BxmlTypeRegistry.fromMachine(parseMachine(MACHINE_XML));
        assertEquals("Set<Set<integer> >", registry.acslVariableLogicTypeFromTypref(3));
    }

    @Test
    void tupleCodomainRelationRegistersFlattenedTypeAndMatchesNamePattern() throws Exception {
        TupleCodomainTypeRegistry callScope = new TupleCodomainTypeRegistry();
        TupleCodomainTypeRegistry.bindForCurrentCall(callScope);
        try {
            BxmlTypeRegistry registry = BxmlTypeRegistry.fromMachine(parseMachine(MACHINE_XML));
            String flatType = registry.acslVariableLogicTypeFromTypref(4);

            assertTrue(
                    BxmlTypeRegistry.TUPLE_CODOMAIN_RELATION_NAME.matcher(flatType).matches(),
                    () -> "expected a flattened Relation_/Function_ tuple-codomain name, got: " + flatType);

            Map<String, String> registered = callScope.snapshotAndClear();
            assertTrue(registered.containsKey(flatType), "registered map should contain " + flatType);
            assertTrue(
                    registered.get(flatType).startsWith("Set<Tuple<"),
                    () -> "expected a Set<Tuple<...>> definition, got: " + registered.get(flatType));

            // snapshotAndClear já esvaziou o registo — uma segunda leitura não repete entradas.
            assertTrue(callScope.snapshotAndClear().isEmpty());
        } finally {
            TupleCodomainTypeRegistry.bindForCurrentCall(null);
        }
    }
}
