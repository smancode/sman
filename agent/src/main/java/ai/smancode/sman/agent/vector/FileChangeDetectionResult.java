package ai.smancode.sman.agent.vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 文件变化检测结果
 *
 * @author SiliconMan Team
 * @since 1.0.0
 */
public class FileChangeDetectionResult {

    /** 新增或修改的文件列表 */
    private final List<String> addedOrModifiedFiles;

    /** 删除的文件列表 */
    private final List<String> deletedFiles;

    public FileChangeDetectionResult(List<String> addedOrModifiedFiles, List<String> deletedFiles) {
        this.addedOrModifiedFiles = addedOrModifiedFiles != null
            ? addedOrModifiedFiles
            : new ArrayList<>();
        this.deletedFiles = deletedFiles != null
            ? deletedFiles
            : new ArrayList<>();
    }

    public List<String> getAddedOrModifiedFiles() {
        return Collections.unmodifiableList(addedOrModifiedFiles);
    }

    public List<String> getDeletedFiles() {
        return Collections.unmodifiableList(deletedFiles);
    }

    public boolean isEmpty() {
        return addedOrModifiedFiles.isEmpty() && deletedFiles.isEmpty();
    }

    public int getTotalChanges() {
        return addedOrModifiedFiles.size() + deletedFiles.size();
    }

    @Override
    public String toString() {
        return String.format(
            "FileChangeDetectionResult{新增/修改=%d, 删除=%d}",
            addedOrModifiedFiles.size(),
            deletedFiles.size()
        );
    }
}
