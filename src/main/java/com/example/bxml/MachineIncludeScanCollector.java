package com.example.bxml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.example.AcslLibIncludes;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Coleta texto/caminhos de outras máquinas {@code .bxml} do projeto para deteção de includes da
 * {@code B2ACSLLib} já usados (evita repetir {@code include} de blocos genéricos já presentes por
 * transitividade SEES/IMPORTS). Extraído de {@code BxmlSetsTranslator} (WMC=317) por extract-class
 * puro: nenhuma linha de lógica mudou, só o arquivo em que vive.
 */
public final class MachineIncludeScanCollector {

    private MachineIncludeScanCollector() {}

    /**
     * Texto das máquinas em {@code SEES} para deteção de includes da {@code B2ACSLLib} na máquina que
     * vê (corpo dos {@code .acsl} já gerados, sem repetir o preâmbulo de {@code include}).
     */
    public static String collectSeesMachinesTextForIncludeScan(
            Element viewerMachineEl, Path bxmlDirectory, Path acslDirectory) {
        return collectMachinesTextForIncludeScan(
                BxmlSetsTranslator.listReferencedMachineNames(viewerMachineEl), bxmlDirectory, acslDirectory);
    }

    /**
     * Texto das máquinas em {@code IMPORTS} para deteção de includes da {@code B2ACSLLib}.
     */
    public static String collectImportedMachinesTextForIncludeScan(
            Element rootMachineEl,
            List<Element> mergedMachineElements,
            Path bxmlDirectory,
            Path acslDirectory) {
        return collectMachinesTextForIncludeScan(
                BxmlSetsTranslator.listImportedMachineNamesFromChain(rootMachineEl, mergedMachineElements),
                bxmlDirectory,
                acslDirectory);
    }

    /**
     * Texto do fecho transitivo {@code SEES}/{@code IMPORTS} para deteção de includes da
     * {@code B2ACSLLib} na raiz de importação.
     */
    public static String collectTransitiveDependencyMachinesTextForIncludeScan(
            String rootMachineName,
            BxmlSeesGraph seesGraph,
            BxmlImportsGraph importsGraph,
            Path bxmlDirectory,
            Path acslDirectory) {
        return collectMachinesTextForIncludeScan(
                BxmlSetsTranslator.transitiveDependencyMachineNames(rootMachineName, seesGraph, importsGraph),
                bxmlDirectory,
                acslDirectory);
    }

    /** Texto de uma máquina (ACSL gerado ou BXML) para varredura de símbolos da lib. */
    public static String collectMachineTextForIncludeScan(
            String machineName, Path bxmlDirectory, Path acslDirectory) {
        if (machineName == null || machineName.isBlank()) {
            return "";
        }
        return collectMachinesTextForIncludeScan(
                List.of(machineName.trim()), bxmlDirectory, acslDirectory);
    }

    /**
     * Includes da {@code B2ACSLLib} já emitidos nos preâmbulos do fecho transitivo de dependências.
     */
    public static List<String> collectLibIncludePathsFromTransitiveDependencies(
            String rootMachineName,
            BxmlSeesGraph seesGraph,
            BxmlImportsGraph importsGraph,
            Path acslDirectory) {
        return collectLibIncludePathsFromMachines(
                BxmlSetsTranslator.transitiveDependencyMachineNames(rootMachineName, seesGraph, importsGraph),
                acslDirectory);
    }

    private static String collectMachinesTextForIncludeScan(
            List<String> machineNames, Path bxmlDirectory, Path acslDirectory) {
        StringBuilder sb = new StringBuilder();
        if (machineNames == null) {
            return "";
        }
        for (String name : machineNames) {
            if (acslDirectory != null) {
                Path acsl = acslDirectory.resolve(name + ".acsl");
                if (Files.isRegularFile(acsl)) {
                    try {
                        sb.append(
                                AcslLibIncludes.acslBodyAfterPreambleIncludes(
                                        Files.readString(acsl)));
                        sb.append('\n');
                    } catch (Exception ignored) {
                        // ignora
                    }
                    continue;
                }
            }
            if (bxmlDirectory != null) {
                Path bxml = bxmlDirectory.resolve(name + ".bxml");
                if (Files.isRegularFile(bxml)) {
                    try {
                        sb.append(
                                collectBxmlTextForIncludeScan(BxmlSetsTranslator.parseMachineElement(bxml)));
                        sb.append('\n');
                    } catch (Exception ignored) {
                        // ignora
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Includes da {@code B2ACSLLib} já emitidos nos preâmbulos dos {@code .acsl} das máquinas vistas
     * (caminhos relativos, para fusão sem duplicar na máquina que vê).
     */
    public static List<String> collectLibIncludePathsFromSeenMachines(
            Element viewerMachineEl, Path bxmlDirectory, Path acslDirectory) {
        return collectLibIncludePathsFromMachines(
                BxmlSetsTranslator.listReferencedMachineNames(viewerMachineEl), acslDirectory);
    }

    /**
     * Includes da {@code B2ACSLLib} nos preâmbulos dos {@code .acsl} das máquinas importadas.
     */
    public static List<String> collectLibIncludePathsFromImportedMachines(
            Element rootMachineEl,
            List<Element> mergedMachineElements,
            Path bxmlDirectory,
            Path acslDirectory) {
        return collectLibIncludePathsFromMachines(
                BxmlSetsTranslator.listImportedMachineNamesFromChain(rootMachineEl, mergedMachineElements),
                acslDirectory);
    }

    private static List<String> collectLibIncludePathsFromMachines(
            List<String> machineNames, Path acslDirectory) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (machineNames == null || acslDirectory == null) {
            return List.copyOf(merged);
        }
        for (String name : machineNames) {
            Path acsl = acslDirectory.resolve(name + ".acsl");
            if (Files.isRegularFile(acsl)) {
                try {
                    merged.addAll(
                            AcslLibIncludes.parseLibIncludeRelativePathsFromPreamble(
                                    Files.readString(acsl)));
                } catch (Exception ignored) {
                    // ignora
                }
            }
        }
        return List.copyOf(merged);
    }

    /** Fragmento BXML traduzido só para varredura de símbolos da lib (conjuntos + invariantes). */
    private static String collectBxmlTextForIncludeScan(Element machineEl) throws Exception {
        String machineName = machineEl.getAttribute("name");
        if (machineName == null || machineName.isBlank()) {
            return "";
        }
        com.example.bxml.BxmlTranslateContext ctx =
                com.example.bxml.BxmlTranslateContext.forMachine(machineEl);
        StringBuilder sb = new StringBuilder();
        String sets = BxmlSetsTranslator.formatSetsBlock(machineEl);
        if (!sets.isBlank()) {
            sb.append(sets).append('\n');
        }
        for (org.w3c.dom.Element inv :
                com.example.bxml.BxmlInvariantTranslator.listDirectInvariants(machineEl)) {
            String body =
                    com.example.bxml.BxmlPredicateToAcsl.translateInvariantContent(inv, ctx);
            if (body != null && !body.isBlank()) {
                sb.append(body).append('\n');
            }
        }
        NodeList opsNl = machineEl.getElementsByTagNameNS("*", "Operations");
        if (opsNl.getLength() > 0) {
            Element operationsEl = (Element) opsNl.item(0);
            NodeList children = operationsEl.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() != Node.ELEMENT_NODE) continue;
                Element op = (Element) n;
                if (!"Operation".equals(op.getLocalName())) continue;
                // Mesma reescrita de parâmetros array-backed (xx : A --> B -> array_to_function_int/
                // _bool(xx, len)) do caminho completo (BxmlOperationsTranslator): sem isto, esta
                // varredura simplificada (usada quando o .acsl real da máquina dependente ainda não
                // foi gerado, por ordem de geração) não vê o símbolo array_to_function_int/_bool e o
                // include correspondente nunca é emitido pela máquina "carrier".
                Map<String, BxmlOperationsTranslator.ArrayFunctionParamInfo> arrayParamLens =
                        BxmlOperationsTranslator.inferArrayFunctionParamLengths(op, ctx);
                Element pre = BxmlDomUtils.firstChildElement(op, "Precondition");
                if (pre != null) {
                    List<String> clauses =
                            new ArrayList<>(
                                    com.example.bxml.BxmlPredicateToAcsl.translatePredicateBlock(pre, ctx));
                    BxmlOperationsTranslator.rewriteAcslListForArrayBackedParams(clauses, arrayParamLens);
                    for (String clause : clauses) {
                        if (clause != null && !clause.isBlank()) {
                            sb.append(clause).append('\n');
                        }
                    }
                }
                Element body = BxmlDomUtils.firstChildElement(op, "Body");
                if (body != null) {
                    List<String> ensures = new ArrayList<>();
                    try {
                        com.example.bxml.BxmlInitialisationTranslator.appendEnsuresFromBody(body, ensures, ctx);
                    } catch (Exception ignored) {
                    }
                    BxmlOperationsTranslator.rewriteAcslListForArrayBackedParams(ensures, arrayParamLens);
                    for (String clause : ensures) {
                        if (clause != null && !clause.isBlank()) {
                            sb.append(clause).append('\n');
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    /** {@code false} para refinamento/implementação com {@code <Abstraction>} preenchido. */
    static boolean machineGeneratesOwnAcslFile(Element machineEl) {
        Element abs = BxmlDomUtils.firstChildElement(machineEl, "Abstraction");
        if (abs == null) {
            return true;
        }
        String t = abs.getTextContent();
        return t == null || t.isBlank();
    }
}
