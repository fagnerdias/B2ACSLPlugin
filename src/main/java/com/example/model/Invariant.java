package com.example.model;

public class Invariant {
    private String machineType;
    private String machineName;
    private String invariantExpression;

    public Invariant(String machineType, String machineName, String invariantExpression) {
        this.machineType = machineType;
        this.machineName = machineName;
        this.invariantExpression = invariantExpression;
    }

    public String getMachineType() {
        return machineType;
    }

    public String getMachineName() {
        return machineName;
    }

    public String getInvariantExpression() {
        return invariantExpression;
    }

    public void setMachineType(String machineType) {
        this.machineType = machineType;
    }

    public void setMachineName(String machineName) {
        this.machineName = machineName;
    }

    public void setInvariantExpression(String invariantExpression) {
        this.invariantExpression = invariantExpression;
    }
}