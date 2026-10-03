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
 * 注意：判定时刻（judgedAt）不参与哈希——同一批数据在不同判定时刻复盘是合法操作。
 */
@Component
public class ContentHasher {

    public String hash(BatchSubmission submission) {
        StringBuilder sb = new StringBuilder();
        sb.append("contract=").append(submission.getContractId()).append('\n');
        sb.append("submittedAt=").append(submission.getSubmittedAt()).append('\n');
        sb.append("businessTime=").append(submission.effectiveBusinessTime()).append('\n');
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
