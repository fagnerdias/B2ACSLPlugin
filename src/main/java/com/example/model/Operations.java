package com.example.model;

public class Operations {
    private String operationName;
    private String operationPrecondition;
    private String operationPostcondition;
    private String operationReturn;

    public Operations(String operationName, String operationPrecondition, String operationPostcondition, String operationReturn) {
        this.operationName = operationName;
        this.operationPrecondition = operationPrecondition;
        this.operationPostcondition = operationPostcondition;
        this.operationReturn = operationReturn;
    }

    public String getOperationName() {
        return operationName;
    }

    public String getOperationPrecondition() {
        return operationPrecondition;
    }

    public String getOperationPostcondition() {
        return operationPostcondition;
    }

    public String getOperationReturn() {
        return operationReturn;
    }

    public void setOperationName(String operationName) {
        this.operationName = operationName;
    }

    public void setOperationPrecondition(String operationPrecondition) {
        this.operationPrecondition = operationPrecondition;
    }

    public void setOperationPostcondition(String operationPostcondition) {
        this.operationPostcondition = operationPostcondition;
    }

    public void setOperationReturn(String operationReturn) {
        this.operationReturn = operationReturn;
    }
}