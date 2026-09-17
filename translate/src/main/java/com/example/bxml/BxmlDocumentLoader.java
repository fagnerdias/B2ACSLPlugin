package com.example.bxml;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Entrada de leitura BXML → DOM usada em todo o pacote {@code bxml} (extraída de {@link
 * BxmlSetsTranslator}, que nominalmente é um "tradutor de conjuntos" mas também acumulou este
 * ponto de entrada de parsing genérico — extract-class puro: nenhuma linha de lógica mudou, só o
 * ficheiro em que vive).
 *
 * <p>Público (não mais package-private) e com cache por caminho: o mesmo {@code .bxml} era
 * reanalisado do disco uma vez por consumidor (dezenas de chamadas ao longo de um único {@code
 * run()} de {@code B2ACSLPipeline}, e mais duas implementações próprias e independentes deste
 * parser — {@code com.example.AcslGenerator#parseMachineElement} e {@code
 * com.example.model.Machine#fromBxmlPath} — antes desta mudança). As três agora convergem aqui:
 * {@code AcslGenerator.parseMachineElement} e {@code Machine.fromBxmlPath} delegam para este
 * método. Cache seguro porque nenhum consumidor no projeto muta a árvore DOM devolvida (sem
 * {@code setAttribute}/{@code appendChild}/{@code removeChild}/… em lado nenhum — verificado por
 * grep no {@code src/main/java} inteiro); um {@code ConcurrentHashMap} é usado por segurança
 * mesmo o pipeline sendo de facto single-threaded.
 */
public final class BxmlDocumentLoader {

    private BxmlDocumentLoader() {}

    private static final Map<Path, Element> PARSED_MACHINE_ELEMENT_CACHE = new ConcurrentHashMap<>();

    /**
     * Raiz {@code <Machine>} com parser namespace-aware (BXML 1.0 com {@code xmlns}). Repetir a
     * mesma {@code bxmlPath} (normalizada/absoluta) devolve a MESMA árvore DOM já analisada, sem
     * reler/reanalisar o ficheiro do disco.
     */
    public static Element parseMachineElement(Path bxmlPath) throws Exception {
        Path key = bxmlPath.toAbsolutePath().normalize();
        Element cached = PARSED_MACHINE_ELEMENT_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        Element parsed = parseMachineElementUncached(bxmlPath);
        // Duas chamadas concorrentes para o mesmo path (não deveria acontecer neste pipeline
        // single-threaded, mas o cache é um ConcurrentHashMap por segurança) podem ambas analisar
        // antes de qualquer uma publicar — putIfAbsent garante que todos os chamadores acabam a
        // ver a MESMA instância, descartando qualquer análise duplicada perdedora.
        Element winner = PARSED_MACHINE_ELEMENT_CACHE.putIfAbsent(key, parsed);
        return winner != null ? winner : parsed;
    }

    private static Element parseMachineElementUncached(Path bxmlPath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // opcional
        }
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(bxmlPath.toFile());
        Element root = doc.getDocumentElement();
        root.normalize();
        return root;
    }
}
