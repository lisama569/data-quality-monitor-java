package com.github.lisama569.dataqualitymonitorjava.service;

import com.github.lisama569.dataqualitymonitorjava.model.BatchSubmission;
import com.github.lisama569.dataqualitymonitorjava.model.DataRow;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * 计算批次内容的稳定哈希，用于重复提交与同 ID 内容冲突的识别。
 * 哈希覆盖：契约标识、提交时间、业务时点、行数、行顺序，以及每行按键名排序后的键值对，
 * 因此同样的内容必然得到相同哈希；任一字段值或列集合不同都会得到不同哈希。
 * 判定时刻仅在调用方显式指定时计入哈希：显式指定的判定时刻是结论口径的一部分，
 * 同一批次以不同判定时刻提交属于不同内容；缺省（取系统当前时间）时不计入，
 * 否则缺省提交的重复判定会因运行时刻不同而永远冲突。
 */
@Component
public class ContentHasher {

    public String hash(BatchSubmission submission) {
        StringBuilder sb = new StringBuilder();
        sb.append("contract=").append(submission.getContractId()).append('\n');
        sb.append("submittedAt=").append(submission.getSubmittedAt()).append('\n');
        sb.append("businessTime=").append(submission.getBusinessTime()).append('\n');
        if (submission.getEvaluationTime() != null) {
            sb.append("evaluationTime=").append(submission.getEvaluationTime()).append('\n');
        }
        sb.append("rowCount=").append(submission.getRows().size()).append('\n');
        for (int i = 0; i < submission.getRows().size(); i++) {
            DataRow row = submission.getRows().get(i);
            sb.append("row[").append(i).append("]={");
            TreeMap<String, String> sorted = new TreeMap<>(row.values());
            for (Map.Entry<String, String> e : sorted.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
            }
            sb.append("}\n");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
