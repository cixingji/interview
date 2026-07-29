package interview.guide.modules.interview.repository;

import interview.guide.modules.interview.model.InterviewAnswerEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 面试答案Repository
 */
@Repository
public interface InterviewAnswerRepository extends JpaRepository<InterviewAnswerEntity, Long> {
    
    /**
     * 根据会话查找所有答案
     */
    List<InterviewAnswerEntity> findBySessionOrderByQuestionIndex(InterviewSessionEntity session);
    
    /**
     * 根据会话ID查找所有答案
     */
    List<InterviewAnswerEntity> findBySessionIdOrderByQuestionIndex(Long sessionId);
    
    /**
     * 根据会话 sessionId 字符串查找所有答案
     */
    List<InterviewAnswerEntity> findBySession_SessionIdOrderByQuestionIndex(String sessionId);

    /**
     * 根据会话 sessionId 和问题索引查找单条答案（用于 upsert）
     */
    Optional<InterviewAnswerEntity> findBySession_SessionIdAndQuestionIndex(String sessionId, Integer questionIndex);

    /**
     * 查询可用于弱项训练诊断的历史证据。
     *
     * <p>只读取整场评估已经完成、答案和数值评分都有效的数据。调用方必须传入 Pageable
     * 限制总量，避免用户历史增长后一次训练创建加载全部答案。
     */
    @Query("""
        SELECT a
        FROM InterviewAnswerEntity a
        JOIN FETCH a.session s
        WHERE s.status = :sessionStatus
          AND s.skillId = :skillId
          AND (:resumeId IS NULL OR s.resumeId = :resumeId)
          AND a.score IS NOT NULL
          AND a.score BETWEEN 0 AND 100
          AND a.category IS NOT NULL
          AND TRIM(a.category) <> ''
          AND a.question IS NOT NULL
          AND TRIM(a.question) <> ''
          AND a.userAnswer IS NOT NULL
          AND TRIM(a.userAnswer) <> ''
        ORDER BY a.answeredAt DESC
        """)
    List<InterviewAnswerEntity> findTrainingEvidence(
        @Param("resumeId") Long resumeId,
        @Param("skillId") String skillId,
        @Param("sessionStatus") InterviewSessionEntity.SessionStatus sessionStatus,
        Pageable pageable
    );
}
