package com.github.lisama569.dataqualitymonitorjava.web;

import java.util.List;

/** 契约登记请求。 */
public record ContractRequest(String contractId, Integer version, List<FieldDef> fields) {
    public record FieldDef(String name, String dataType, boolean required) {
    }
}
