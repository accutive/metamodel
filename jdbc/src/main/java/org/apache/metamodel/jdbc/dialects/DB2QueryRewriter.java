/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.metamodel.jdbc.dialects;

import org.apache.metamodel.jdbc.JdbcDataContext;
import org.apache.metamodel.query.FilterItem;
import org.apache.metamodel.query.OperatorType;
import org.apache.metamodel.query.Query;
import org.apache.metamodel.query.SelectItem;
import org.apache.metamodel.schema.Column;
import org.apache.metamodel.schema.ColumnType;
import org.apache.metamodel.util.DateUtils;
import org.apache.metamodel.util.FormatHelper;
import org.apache.metamodel.util.TimeComparator;

import java.sql.Blob;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Date;

/**
 * Query rewriter for IBM DB2
 */
public class DB2QueryRewriter extends RowNumberQueryRewriter {

    public DB2QueryRewriter(JdbcDataContext dataContext) {
        super(dataContext);
    }

    @Override
    public String escapeQuotes(String filterItemOperand) {
        return filterItemOperand.replaceAll("\\'", "\\\\'");
    }

    /**
     * DB2 expects the fully qualified column name, including schema, in select
     * items.
     */
    @Override
    public boolean isSchemaIncludedInColumnPaths() {
        return true;
    }

    @Override
    public boolean isMaxRowsSupported() {
        return true;
    }

    @Override
    public boolean isFirstRowSupported(final Query query) {
        return true;
    }

    @Override
    public String rewriteQuery(Query query) {
        final Integer firstRow = query.getFirstRow();
        final Integer maxRows = query.getMaxRows();

        if (maxRows == null && (firstRow == null || firstRow.intValue() == 1)) {
            return super.rewriteQuery(query);
        }

        if ((firstRow == null || firstRow.intValue() == 1) && maxRows != null && maxRows > 0) {
            // We prefer to use the "FETCH FIRST [n] ROWS ONLY" approach, if
            // firstRow is not specified.
            return super.rewriteQuery(query) + " FETCH FIRST " + maxRows + " ROWS ONLY";

        } else {
            // build a ROW_NUMBER() query like this:

            // SELECT [original select clause]
            // FROM ([original select clause],
            // ROW_NUMBER() AS metamodel_row_number
            // FROM [remainder of regular query])
            // WHERE metamodel_row_number BETWEEN [firstRow] and [maxRows];
            return getRowNumberSql(query, maxRows, firstRow);
        }
    }

    @Override
    public String rewriteColumnType(ColumnType columnType, Integer columnSize) {
        if (columnType == ColumnType.BOOLEAN || columnType == ColumnType.BIT) {
            return "SMALLINT";
        }
        return super.rewriteColumnType(columnType, columnSize);
    }

    @Override
    public String rewriteFilterItem(FilterItem item) {
        final SelectItem selectItem = item.getSelectItem();
        final Object itemOperand = item.getOperand();
        final OperatorType operator = item.getOperator();
        if (null != selectItem && itemOperand != null) {
            ColumnType columnType = selectItem.getExpectedColumnType();
            if (columnType != null) {
                if (columnType.isTimeBased()) {
                    // special logic for DB2 based time operands.

                    StringBuilder sb = new StringBuilder();
                    sb.append(selectItem.getSameQueryAlias(true));
                    final Object operand = FilterItem.appendOperator(sb, itemOperand, operator);

                    if (operand instanceof SelectItem) {
                        final String selectItemString = ((SelectItem) operand).getSameQueryAlias(true);
                        sb.append(selectItemString);
                    } else {
                        // allow for parameterized queries
                        if ("?".equals(itemOperand.toString())) {
                            sb.append(itemOperand);
                        }
                        else {
                            Date date = TimeComparator.toDate(itemOperand);
                            if (date == null) {
                                throw new IllegalStateException("Could not convert " + itemOperand + " to date");
                            }

                            final String sqlValue = FormatHelper.formatSqlTime(columnType, date, true, "('", "')");
                            sb.append(sqlValue);
                        }
                    }

                    return sb.toString();
                }
            }
        }
        return super.rewriteFilterItem(item);
    }

    @Override
    public void setStatementParameter(PreparedStatement st, int valueIndex, Column column, Object value)
            throws SQLException {

        final ColumnType type = (column == null ? null : column.getType());

        // handle case where data to be inserted is binary, but column type is literal
        if (type.isLiteral()) {
            if (value instanceof byte[]) {
                st.setObject(valueIndex, value);
                return;
            }
            else if(value instanceof Blob) {
                st.setObject(valueIndex, ((Blob) value).getBytes(1, (int) ((Blob) value).length()));
                return;
            }
        }

        super.setStatementParameter(st, valueIndex, column, value);
    }


}