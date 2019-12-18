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

import java.io.BufferedReader;
import java.io.CharArrayReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.CharBuffer;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.metamodel.jdbc.JdbcDataContext;
import org.apache.metamodel.query.FilterItem;
import org.apache.metamodel.schema.Column;
import org.apache.metamodel.schema.ColumnType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Query rewriter for Oracle
 */
public class OracleQueryRewriter extends OffsetFetchQueryRewriter {

    private static final Logger logger = LoggerFactory.getLogger(OracleQueryRewriter.class);

	public static final int FIRST_FETCH_SUPPORTING_VERSION = 12;

    public OracleQueryRewriter(JdbcDataContext dataContext) {
        super(dataContext, FIRST_FETCH_SUPPORTING_VERSION, false);
    }

    @Override
    public ColumnType getColumnType(int jdbcType, String nativeType, Integer columnSize) {
        // For TIMESTAMP WITH LOCAL_TIME_ZONE/TIME_ZONE, which jdbcType is -102/-101
        if (nativeType.contains("TIMESTAMP")) {
            return ColumnType.TIMESTAMP;
        }
        return super.getColumnType(jdbcType, nativeType, columnSize);
    }

    @Override
    public String rewriteColumnType(ColumnType columnType, Integer columnSize) {
        if (columnType == ColumnType.NUMBER || columnType == ColumnType.NUMERIC || columnType == ColumnType.DECIMAL) {
            // as one of the only relational databases out there, Oracle has a
            // NUMBER type. For this reason NUMBER would be replaced by the
            // super-type's logic, but we handle it specifically here.
            super.rewriteColumnTypeInternal("NUMBER", columnSize);
        }
        if (columnType == ColumnType.BOOLEAN || columnType == ColumnType.BIT) {
            // Oracle has no boolean type, but recommends NUMBER(3) or CHAR(1).
            // For consistency with most other databases who have either a
            // boolean or a bit, we use the number variant because it's return
            // values (0 or 1) can be converted the most easily back to a
            // boolean.
            return "NUMBER(3)";
        }
        if (columnType == ColumnType.DOUBLE) {
            return "BINARY_DOUBLE";
        }
        if (columnType == ColumnType.FLOAT) {
            return "BINARY_FLOAT";
        }
        if (columnType == ColumnType.BINARY || columnType == ColumnType.VARBINARY) {
            return "RAW";
        }

        // following conversions based on
        // http://docs.oracle.com/cd/B19306_01/gateways.102/b14270/apa.htm
        if (columnType == ColumnType.TINYINT) {
            return "NUMBER(3)";
        }
        if (columnType == ColumnType.SMALLINT) {
            return "NUMBER(5)";
        }
        if (columnType == ColumnType.INTEGER) {
            return "NUMBER(10)";
        }
        if (columnType == ColumnType.BIGINT) {
            return "NUMBER(19)";
        }

        // Oracle has no "time only" data type but 'date' also includes time
        if (columnType == ColumnType.TIME) {
            super.rewriteColumnType(ColumnType.DATE, columnSize);
        }
        return super.rewriteColumnType(columnType, columnSize);
    }

    @Override
    public String rewriteFilterItem(final FilterItem item) {
        if (item.getOperand() instanceof String && item.getOperand().equals("")) {
            // In Oracle empty strings are treated as null. Typical SQL constructs with an empty string do not work.
            return super.rewriteFilterItem(new FilterItem(item.getSelectItem(), item.getOperator(), null));
        } else {
            return super.rewriteFilterItem(item);
        }
    }
    
	@Override
	public Object getResultSetValue(ResultSet resultSet, int columnIndex, Column column) throws SQLException {
        // For Oracle CLOB and BLOB objects over 4kb, the "value" is only a pointer to the actual data.
        // If the destination insert is on a different instance than the source, this pointer
        // will not reference an actual object on the database server, and an exception will
        // be thrown. If the destination data store is not Oracle, the resulting CLOB/BLOB
		// column will not have the correct binary data.
		// Instead of returning the original CLOB/BLOB, return a reader/stream with the data.
		// Oracle requires that the data be read before the next column in the result set row
		// is accessed, so we must copy to a new reader/stream.
		// See Oracle bug 29126086 (ORA-00942 Received when PreparedStatement.setClob used)
		// (not technically a bug, but a nasty side effect of a clever Oracle optimization)
       if (column.getNativeType() != null) {
            switch (column.getNativeType()) {
            case "clob":
            	return getClobData(resultSet, columnIndex, column);
            case "nclob":
                return resultSet.getNClob(columnIndex).getCharacterStream();
            case "blob":
                return resultSet.getBlob(columnIndex).getBinaryStream();
            }
        }
        return super.getResultSetValue(resultSet, columnIndex, column);
    }

	/**
	 * Returns a Reader object containing the underlying data for the CLOB column.
	 * @param resultSet
	 * @param columnIndex
	 * @param column
	 * @return Reader
	 * @throws SQLException
	 */
	private Object getClobData(ResultSet resultSet, int columnIndex, Column column) throws SQLException {
		final Clob clob =  resultSet.getClob(columnIndex);
		if (clob == null) {
			return clob;
		}
		final long length = clob.length();
		final Reader reader = clob.getCharacterStream();
		if (length > 4096 * 100) {
			// handle with a temporary file as backing stream
			final BufferedReader br = new BufferedReader(reader);
			final CharBuffer target = CharBuffer.allocate(4096);

			try {
				// write the CLOB data into a temp file
				final File tempFile = File.createTempFile("clob", null);
				
				final Writer tempFileWriter = new FileWriter(tempFile);
				while (br.read(target) >= 0) {
					tempFileWriter.write(target.array());
				}
				tempFileWriter.close();
				
				// return a BufferedInputStream that deletes the underlying file when closed
				return new BufferedReader(new TempFileReader(tempFile));
				
			} catch (IOException e) {
				final String msg ="Failed to get CLOB data for column: " + column.getQualifiedLabel();
		        logger.error(msg);
		        throw new SQLException(msg, e);
			}
		}
		else {
			// use in-memory buffer
			final char cbuf[] = new char[(int)length];
			try {
				reader.read(cbuf);
			} catch (IOException e) {
				final String msg ="Failed to get CLOB data for column: " + column.getQualifiedLabel();
		        logger.error(msg);
		        throw new SQLException(msg, e);
			}
			finally {
				try {
					reader.close();
				}
				catch (IOException e) {
					logger.error("Failed to close CLOB reader");
					// rethrow?
				}
			}
			return new CharArrayReader(cbuf);
			
		}
	}

	/**
	 * Inner class to wrap a temporary file with a Reader, such that closing
	 * the Reader also deletes the underlying temporary file
	 * @author paul
	 *
	 */
	class TempFileReader extends FileReader {

		private File tempFile;
		
		public TempFileReader(File file) throws FileNotFoundException {
			super(file);
			tempFile = file;
		}

		@Override
		public void close() throws IOException {
			super.close();
			tempFile.delete();
		}
		
		
	}
}
