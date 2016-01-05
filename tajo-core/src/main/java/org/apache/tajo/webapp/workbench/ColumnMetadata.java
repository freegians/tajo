package org.apache.tajo.webapp.workbench;

/**
 * Created by freegians on 2016. 1. 4..
 */
public class ColumnMetadata {
    private String columnName;
    private String columnType;

    public String getColumnName() {
        return columnName;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public ColumnMetadata withColumnName(String columnName) {
        this.columnName = columnName;
        return this;
    }

    public String getColumnType() {
        return columnType;
    }

    public void setColumnType(String columnType) {
        this.columnType = columnType;
    }

    public ColumnMetadata withColumnType(String columnType) {
        this.columnType = columnType;
        return this;
    }
}
