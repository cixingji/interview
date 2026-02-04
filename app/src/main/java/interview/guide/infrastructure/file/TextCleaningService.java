package interview.guide.infrastructure.file;

import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

/**
 * 文本清理服务
 * 提供统一的文本内容清理和规范化功能
 */
@Service
public class TextCleaningService {

    /**
     * 清理和规范化文本内容
     * 
     * 格式级清理：
     * - 规范化换行符
     * - 去除连续多个空行
     * - 去除行首行尾多余空格
     * 
     * 语义级过滤（简历场景化）：
     * - 去除图片文件名（整行匹配，防止误杀）
     * - 去除 Tika PDF 临时文件路径
     * - 去除 HTTP 图片链接
     * - 去除只有符号的分隔线
     * 
     * 作为 RAG/AI 分析前的"保险层"，确保文本质量
     *
     * @param text 原始文本
     * @return 清理后的文本
     */
    public String cleanText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        return text
            // ========== 语义过滤层（简历场景化）==========
            // 1. 去除图片文件名（整行匹配，防止误删正文中的字符串）
            .replaceAll("(?m)^image\\d+\\.(png|jpg|jpeg|gif)$", "")
            
            // 2. 去除 Tika PDF 临时文件路径
            .replaceAll("file:///.*?\\.html\\?query=\\d+", "")
            
            // 3. 去除 HTTP/HTTPS 图片链接
            .replaceAll("https?://[^\\s]*?\\.(png|jpe?g|gif|bmp|webp)", "")
            
            // 4. 去除只有符号的分隔线（整行匹配）
            .replaceAll("(?m)^[-_*=]{3,}$", "")
            
            // ========== 格式清理层 ==========
            // 5. 规范化换行符
            .replaceAll("\\r\\n", "\n")
            .replaceAll("\\r", "\n")
            
            // 6. 去除连续多个空行，保留最多两个
            .replaceAll("\\n{3,}", "\n\n")
            
            // 7. 去除行首行尾多余空格
            .lines()
            .map(String::strip)
            .filter(line -> !line.isEmpty()) // 过滤空行（由语义过滤产生的）
            .collect(Collectors.joining("\n"))
            .strip();
    }

    /**
     * 清理文本并限制最大长度
     *
     * @param text      原始文本
     * @param maxLength 最大长度
     * @return 清理后的文本（可能被截断）
     */
    public String cleanTextWithLimit(String text, int maxLength) {
        String cleaned = cleanText(text);
        if (cleaned.length() > maxLength) {
            return cleaned.substring(0, maxLength);
        }
        return cleaned;
    }

    /**
     * 清理文本并移除所有换行符（转为空格）
     * 适用于需要单行显示的场景
     *
     * @param text 原始文本
     * @return 单行文本
     */
    public String cleanToSingleLine(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        return text
            .replaceAll("\\r\\n", " ")
            .replaceAll("\\r", " ")
            .replaceAll("\\n", " ")
            .replaceAll("\\s+", " ")
            .strip();
    }

    /**
     * 移除 HTML 标签
     *
     * @param text 可能包含 HTML 的文本
     * @return 纯文本
     */
    public String stripHtml(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        return text
            .replaceAll("<[^>]+>", " ")
            .replaceAll("&nbsp;", " ")
            .replaceAll("&amp;", "&")
            .replaceAll("&lt;", "<")
            .replaceAll("&gt;", ">")
            .replaceAll("&quot;", "\"")
            .replaceAll("\\s+", " ")
            .strip();
    }
}
