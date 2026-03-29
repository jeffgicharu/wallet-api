package com.wallet.service;

import com.wallet.entity.AuditLog;
import com.wallet.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    public void log(String action, String targetType, String targetId, String actor, String detail) {
        auditLogRepository.save(AuditLog.builder()
                .action(action)
                .targetType(targetType)
                .targetId(targetId)
                .actor(actor)
                .detail(detail)
                .build());
    }

    public List<AuditLog> getByTarget(String targetType, String targetId) {
        return auditLogRepository.findByTargetTypeAndTargetIdOrderByCreatedAtDesc(targetType, targetId);
    }

    public Page<AuditLog> getAll(Pageable pageable) {
        return auditLogRepository.findAllByOrderByCreatedAtDesc(pageable);
    }
}
