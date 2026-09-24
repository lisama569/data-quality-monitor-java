package com.github.lisama569.dataqualitymonitorjava.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 数据契约：批次结构必须与某一版本的契约一致。 */
public final class DataContract {

    private final String contractId;
    private final int version;
    private final List<FieldSchema> fields;
    private final Instant createdAt;

    public DataContract(String contractId, int version, List<FieldSchema> fields, Instant createdAt) {
        this.contractId = contractId;
        this.version = version;
        this.fields = List.copyOf(fields);
        this.createdAt = createdAt;
    }

    public String getContractId() { return contractId; }
    public int getVersion() { return version; }
    public List<FieldSchema> getFields() { return fields; }
    public Instant getCreatedAt() { return createdAt; }

    /** 按字段名查找声明。 */
    public Optional<FieldSchema> field(String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst();
    }

    /** 契约是否声明了该列。 */
    public boolean hasColumn(String name) {
        return fields.stream().anyMatch(f -> f.name().equals(name));
    }

    /** 契约声明的全部列名。 */
    public java.util.Set<String> columnNames() {
        return fields.stream().map(FieldSchema::name).collect(java.util.stream.Collectors.toSet());
    }
}
