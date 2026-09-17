package com.example.model;

import java.nio.file.Path;

import org.w3c.dom.Element;

import com.example.bxml.BxmlDocumentLoader;

/**
 * Máquina B: nome mais, agora, a raiz {@code <Machine>} do seu {@code .bxml} já analisada — ver
 * {@link #getMachineElement()}. Antes, {@link #fromBxmlPath} tinha a sua PRÓPRIA implementação de
 * parsing DOM independente (3ª cópia no projeto, ao lado de {@code
 * com.example.bxml.BxmlDocumentLoader} e {@code com.example.AcslGenerator}), e só guardava o nome
 * — qualquer chamador que também precisasse do {@code Element} tinha de reanalisar o mesmo
 * ficheiro à parte. Agora delega para {@link BxmlDocumentLoader#parseMachineElement} (cache
 * por-caminho) e guarda o resultado, para que um {@code MachineFile} (nome + {@code Path} +
 * {@code Machine}) já em scope não precise de reanalisar para obter o {@code Element}.
 */
public class Machine {
    private final String machineName;
    private final Element machineElement;

    public Machine(String machineName) {
        this(machineName, null);
    }

    public Machine(String machineName, Element machineElement) {
        this.machineName = machineName;
        this.machineElement = machineElement;
    }

    public String getMachineName() {
        return machineName;
    }

    /**
     * Raiz {@code <Machine>} já analisada (ver {@link #fromBxmlPath}), ou {@code null} quando a
     * máquina foi construída sem um {@code .bxml} de origem (ex.: {@code new Machine(name)} direto).
     */
    public Element getMachineElement() {
        return machineElement;
    }

    /**
     * Lê um arquivo {@code .bxml} pelo caminho e inicializa a máquina.
     */
    public static Machine fromBxmlPath(Path bxmlPath) throws Exception {
        Element machineEl = BxmlDocumentLoader.parseMachineElement(bxmlPath); // <Machine ...>
        String machineName = machineEl.getAttribute("name");
        return new Machine(machineName, machineEl);
    }
}
