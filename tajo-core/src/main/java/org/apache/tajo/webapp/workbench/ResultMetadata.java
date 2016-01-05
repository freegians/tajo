package org.apache.tajo.webapp.workbench;

import java.util.List;

/**
 * Created by freegians on 2016. 1. 4..
 */
public class ResultMetadata {
    private List<ColumnMetadata> columnMetadata;

    public List<ColumnMetadata> getColumnMetadata() {
        return columnMetadata;
    }

    public void setColumnMetadata(List<ColumnMetadata> columnMetadata) {
        this.columnMetadata = columnMetadata;
    }

    public ResultMetadata withColumnMetadata(List<ColumnMetadata> columnMetadata) {
        this.columnMetadata = columnMetadata;
        return this;
    }
}
