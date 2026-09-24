package com.github.lisama569.dataqualitymonitorjava.contract;

import java.util.List;

/**
 * 某一版本的数据契约：字段结构 + 质量规则。
 * 批次评估时会把当时生效的契约版本号随结论一起持久化，保证规则变更后历史结论可复现。
 */
public record DataContract(String dataset, int version, List<FieldSpec> fields, List<QualityRule> rules) {

    public FieldSpec fieldSpec(String name) {
        for (FieldSpec field : fields) {
            if (field.name().equals(name)) {
                return field;
            }
        }
        return null;
    }

    public boolean hasField(String name) {
        return fieldSpec(name) != null;
    }
}
