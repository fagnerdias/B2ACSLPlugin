package com.example.model;

public class Variables {
    private String machineType;
    private String machineName;
    private String variableName;
    private String variableType;
    private boolean isAbstract;
    private boolean isConcrete;

    public Variables(String machineType, String machineName, String variableName, String variableType) {
        this.machineType = machineType;
        this.machineName = machineName;
        this.variableName = variableName;
        this.variableType = variableType;
    }

    public String getMachineType() {
        return machineType;
    }

    public String getMachineName() {
        return machineName;
    }

    public String getVariableName() {
        return variableName;
    }

    public String getVariableType() {
        return variableType;
    }

    public void setMachineType(String machineType) {
        this.machineType = machineType;
    }

    public void setMachineName(String machineName) {
        this.machineName = machineName;
    }

    public void setVariableName(String variableName) {
        this.variableName = variableName;
    }

    public void setVariableType(String variableType) {
        this.variableType = variableType;
    }

    public boolean isAbstract() {
        return isAbstract;
    }

    public boolean isConcrete() {
        return isConcrete;
    }

    public void setAbstract(boolean isAbstract) {
        this.isAbstract = isAbstract;
    }

    public void setConcrete(boolean isConcrete) {
        this.isConcrete = isConcrete;
    }
}