package com.github.lisama569.dataqualitymonitorjava.engine;

import com.github.lisama569.dataqualitymonitorjava.contract.DataContract;
import com.github.lisama569.dataqualitymonitorjava.contract.FieldSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * 结构漂移检测：
 * - 出现契约未声明的字段 -> SCHEMA_DRIFT_UNKNOWN_FIELD
 * - 任一行缺少契约必填字段(键缺失) -> SCHEMA_DRIFT_MISSING_REQUIRED_FIELD
 * 两类漂移可同时报告；行内字段值为空属于完整性规则范畴，不在此处理。
 */
@Component
public class SchemaValidator {

    public SchemaValidationResult validate(DataContract contract, List<Map<String, Object>> rows) {
        Set<String> contractFields = new HashSet<>();
        List<String> required = new ArrayList<>();
        for (FieldSpec spec : contract.fields()) {
            contractFields.add(spec.name());
            if (spec.required()) {
                required.add(spec.name());
            }
        }

        Set<String> unknown = new TreeSet<>();
        Set<String> missing = new TreeSet<>();
        for (Map<String, Object> row : rows) {
            for (String key : row.keySet()) {
                if (!contractFields.contains(key)) {
                    unknown.add(key);
                }
            }
            for (String name : required) {
                if (!row.containsKey(name)) {
                    missing.add(name);
                }
            }
        }
        return new SchemaValidationResult(unknown.isEmpty() && missing.isEmpty(),
                List.copyOf(missing), List.copyOf(unknown));
    }
}
