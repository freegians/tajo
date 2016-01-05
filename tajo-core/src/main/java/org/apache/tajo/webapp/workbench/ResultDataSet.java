package org.apache.tajo.webapp.workbench;

/**
 * Created by freegians on 2016. 1. 4..
 */
public class ResultDataSet {
    private ResultMetadata metadata;
    private String[][] data;

    public ResultMetadata getMetadata() {
        return metadata;
    }

    public void setMetadata(ResultMetadata metadata) {
        this.metadata = metadata;
    }

    public ResultDataSet withMetadata(ResultMetadata metadata) {
        this.metadata = metadata;
        return this;
    }

    public String[][] getData() {
        return data;
    }

    public void setData(String[][] data) {
        this.data = data;
    }

    public ResultDataSet withData(String[][] data) {
        this.data = data;
        return this;
    }
}
