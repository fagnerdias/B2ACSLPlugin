package com.example.bxml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;

/**
 * Testes unitários para {@link BxmlDomUtils} contra fragmentos BXML mínimos, moldados na forma
 * real usada pelo Atelier B (ver {@code examples/Customer_estr/bdp/Set.bxml}: cada elemento traz
 * um {@code <Attr>} de metadados como PRIMEIRO filho, antes do conteúdo real) — o mesmo padrão
 * que {@code firstPredChild}/{@code firstNonAttrElementChild}/{@code twoDirectPredChildren}/
 * {@code directExpChildren} existem para saltar.
 */
class BxmlDomUtilsTest {

    /** Mesma configuração de parser defensiva usada em produção (ver {@link BxmlDocumentLoader}). */
    private static Element parse(String xmlFragment) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // opcional
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc =
                builder.parse(
                        new InputSource(
                                new ByteArrayInputStream(xmlFragment.getBytes(StandardCharsets.UTF_8))));
        Element root = doc.getDocumentElement();
        root.normalize();
        return root;
    }

    private static final String NS = "xmlns='https://www.atelierb.eu/Formats/bxml'";

    @Test
    void firstChildElementFindsMatchingLocalNameAmongSiblings() throws Exception {
        Element root =
                parse(
                        "<Exp_Comparison " + NS + " op=':'>"
                                + "<Attr><Pos l='1' c='1' s='1'/></Attr>"
                                + "<Id value='ee' typref='5'/>"
                                + "<Id value='GOODS' typref='4'/>"
                                + "</Exp_Comparison>");
        Element attr = BxmlDomUtils.firstChildElement(root, "Attr");
        assertEquals("Attr", attr.getLocalName());

        Element id = BxmlDomUtils.firstChildElement(root, "Id");
        assertEquals("ee", id.getAttribute("value"));
    }

    @Test
    void firstChildElementReturnsNullWhenNoMatchOrParentNull() {
        assertNull(BxmlDomUtils.firstChildElement(null, "Id"));
    }

    @Test
    void firstChildElementReturnsNullWhenLocalNameAbsent() throws Exception {
        Element root = parse("<Machine " + NS + " name='Set'><Sees/></Machine>");
        assertNull(BxmlDomUtils.firstChildElement(root, "Invariant"));
    }

    @Test
    void firstPredChildSkipsLeadingAttrMetadataBlock() throws Exception {
        Element root =
                parse(
                        "<Exp_Comparison " + NS + " op='&lt;:'>"
                                + "<Attr><Pos l='9' c='9' s='2'/></Attr>"
                                + "<Id value='set' typref='4'/>"
                                + "<Id value='GOODS' typref='4'/>"
                                + "</Exp_Comparison>");
        Element first = BxmlDomUtils.firstPredChild(root);
        assertEquals("Id", first.getLocalName());
        assertEquals("set", first.getAttribute("value"));
    }

    @Test
    void firstNonAttrElementChildSkipsLeadingAttr() throws Exception {
        Element root =
                parse(
                        "<Precondition " + NS + ">"
                                + "<Attr><Pos l='1' c='1' s='1'/></Attr>"
                                + "<Exp_Comparison op=':'/>"
                                + "</Precondition>");
        Element real = BxmlDomUtils.firstNonAttrElementChild(root);
        assertEquals("Exp_Comparison", real.getLocalName());
    }

    @Test
    void twoDirectPredChildrenReturnsFirstTwoNonAttrOperandsInOrder() throws Exception {
        Element root =
                parse(
                        "<Exp_Comparison " + NS + " op='&lt;:'>"
                                + "<Attr><Pos l='9' c='9' s='2'/></Attr>"
                                + "<Id value='set' typref='4'/>"
                                + "<Id value='GOODS' typref='4'/>"
                                + "</Exp_Comparison>");
        Element[] operands = BxmlDomUtils.twoDirectPredChildren(root);
        assertEquals("set", operands[0].getAttribute("value"));
        assertEquals("GOODS", operands[1].getAttribute("value"));
    }

    @Test
    void twoDirectPredChildrenLeavesSecondSlotNullWhenOnlyOneOperand() throws Exception {
        Element root =
                parse(
                        "<Exp_Comparison " + NS + " op=':'>"
                                + "<Attr><Pos l='1' c='1' s='1'/></Attr>"
                                + "<Id value='ee' typref='5'/>"
                                + "</Exp_Comparison>");
        Element[] operands = BxmlDomUtils.twoDirectPredChildren(root);
        assertEquals("ee", operands[0].getAttribute("value"));
        assertNull(operands[1]);
    }

    @Test
    void directExpChildrenReturnsAllNonAttrChildrenInOrder() throws Exception {
        Element root =
                parse(
                        "<Exp_Comparison " + NS + " op='&lt;:'>"
                                + "<Attr><Pos l='9' c='9' s='2'/></Attr>"
                                + "<Id value='a' typref='4'/>"
                                + "<Id value='b' typref='4'/>"
                                + "<Id value='c' typref='4'/>"
                                + "</Exp_Comparison>");
        List<Element> children = BxmlDomUtils.directExpChildren(root);
        assertEquals(3, children.size());
        assertEquals("a", children.get(0).getAttribute("value"));
        assertEquals("c", children.get(2).getAttribute("value"));
    }

    @Test
    void normalizeColonLikeOpTrimsAndPreservesBareColon() {
        assertEquals("", BxmlDomUtils.normalizeColonLikeOp(null));
        assertEquals(":", BxmlDomUtils.normalizeColonLikeOp(":"));
        assertEquals(":", BxmlDomUtils.normalizeColonLikeOp("  :  "));
        assertEquals("<:", BxmlDomUtils.normalizeColonLikeOp(" <: "));
        assertTrue(BxmlDomUtils.normalizeColonLikeOp("   ").isEmpty());
    }
}
