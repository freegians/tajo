/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
