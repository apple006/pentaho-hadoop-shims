/*! ******************************************************************************
 *
 * Pentaho Data Integration
 *
 * Copyright (C) 2017 by Pentaho : http://www.pentaho.com
 *
 *******************************************************************************
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 ******************************************************************************/
package org.pentaho.hadoop.shim.common.format.parquet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.mapreduce.RecordReader;

//#if shim_type=="HDP" || shim_type=="EMR" || shim_type=="HDI"
import org.apache.parquet.io.api.Binary;
import org.apache.parquet.io.api.Converter;
import org.apache.parquet.io.api.GroupConverter;
import org.apache.parquet.io.api.PrimitiveConverter;
import org.apache.parquet.io.api.RecordConsumer;
import org.apache.parquet.io.api.RecordMaterializer;
import org.apache.parquet.schema.MessageType;
import org.apache.parquet.schema.PrimitiveType;
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName;
import org.apache.parquet.schema.Type;
import org.apache.parquet.schema.Type.Repetition;
//#endif
//#if shim_type=="CDH" || shim_type=="MAPR"
//$import parquet.io.api.Binary;
//$import parquet.io.api.Converter;
//$import parquet.io.api.GroupConverter;
//$import parquet.io.api.PrimitiveConverter;
//$import parquet.io.api.RecordConsumer;
//$import parquet.io.api.RecordMaterializer;
//$import parquet.schema.MessageType;
//$import parquet.schema.PrimitiveType;
//$import parquet.schema.PrimitiveType.PrimitiveTypeName;
//$import parquet.schema.Type;
//$import parquet.schema.Type.Repetition;
//#endif

import org.pentaho.di.core.RowMetaAndData;
import org.pentaho.di.core.exception.KettleValueException;
import org.pentaho.di.core.row.RowMeta;
import org.pentaho.di.core.row.ValueMetaInterface;
import org.pentaho.di.core.row.value.ValueMetaBigNumber;
import org.pentaho.di.core.row.value.ValueMetaBinary;
import org.pentaho.di.core.row.value.ValueMetaBoolean;
import org.pentaho.di.core.row.value.ValueMetaInteger;
import org.pentaho.di.core.row.value.ValueMetaNumber;
import org.pentaho.di.core.row.value.ValueMetaSerializable;
import org.pentaho.di.core.row.value.ValueMetaString;
import org.pentaho.hadoop.shim.api.format.SchemaDescription;

/**
 * Converter for read/write Pentaho row from/into Parquet files.
 * 
 * @author Alexander Buloichik
 */
public class ParquetConverter {
  public static final int PARQUET_JOB_ID = Integer.MAX_VALUE;
  public static final String PARQUET_SCHEMA_CONF_KEY = "PentahoParquetSchema";

  private final SchemaDescription schema;

  public ParquetConverter( SchemaDescription schema ) {
    this.schema = schema;
  }

  public MessageType createParquetSchema() {
    List<Type> types = new ArrayList<>();

    schema.forEach( f -> types.add( convertField( f ) ) );

    return new MessageType( "parquet-schema", types );
  }

  public static SchemaDescription createSchemaDescription( MessageType schema ) {
    SchemaDescription r = new SchemaDescription();

    schema.getFields().forEach( t -> r.addField( convertField( r, t ) ) );

    return r;
  }

  private static SchemaDescription.Field convertField( SchemaDescription schema, Type t ) {
    boolean allowNull = t.getRepetition() != Repetition.REQUIRED;
    switch ( t.asPrimitiveType().getPrimitiveTypeName() ) {
      case BINARY:
        return schema.new Field( t.getName(), t.getName(), ValueMetaInterface.TYPE_STRING, allowNull );
      case BOOLEAN:
        return schema.new Field( t.getName(), t.getName(), ValueMetaInterface.TYPE_BOOLEAN, allowNull );
      case DOUBLE:
        return schema.new Field( t.getName(), t.getName(), ValueMetaInterface.TYPE_NUMBER, allowNull );
      case FLOAT:
        return schema.new Field( t.getName(), t.getName(), ValueMetaInterface.TYPE_NUMBER, allowNull );
      case INT32:
        return schema.new Field( t.getName(), t.getName(), ValueMetaInterface.TYPE_INTEGER, allowNull );
      case INT64:
        return schema.new Field( t.getName(), t.getName(), ValueMetaInterface.TYPE_INTEGER, allowNull );
      default:
        throw new RuntimeException( "Undefined type: " + t );
    }
  }

  private PrimitiveType convertField( SchemaDescription.Field f ) {
    Repetition rep = f.allowNull ? Repetition.OPTIONAL : Repetition.REQUIRED;
    switch ( f.pentahoValueMetaType ) {
      case ValueMetaInterface.TYPE_NUMBER:
        return new PrimitiveType( rep, PrimitiveTypeName.DOUBLE, f.formatFieldName );
      case ValueMetaInterface.TYPE_STRING:
        return new PrimitiveType( rep, PrimitiveTypeName.BINARY, f.formatFieldName );
      case ValueMetaInterface.TYPE_BOOLEAN:
        return new PrimitiveType( rep, PrimitiveTypeName.BOOLEAN, f.formatFieldName );
      case ValueMetaInterface.TYPE_INTEGER:
        return new PrimitiveType( rep, PrimitiveTypeName.INT64, f.formatFieldName );
      case ValueMetaInterface.TYPE_BIGNUMBER:
        return new PrimitiveType( rep, PrimitiveTypeName.DOUBLE, f.formatFieldName );
      case ValueMetaInterface.TYPE_SERIALIZABLE:
        return new PrimitiveType( rep, PrimitiveTypeName.BINARY, f.formatFieldName );
      case ValueMetaInterface.TYPE_BINARY:
        return new PrimitiveType( rep, PrimitiveTypeName.BINARY, f.formatFieldName );
      default:
        throw new RuntimeException( "Undefined type: " + f.pentahoValueMetaType );
    }
  }

  private void writeField( SchemaDescription.Field field, int index, RowMetaAndData row, RecordConsumer consumer )
    throws KettleValueException {
    int fieldIndex = row.getRowMeta().indexOfValue( field.pentahoFieldName );
    if ( fieldIndex < 0 ) {
      return;
    }
    if ( field.allowNull && row.isEmptyValue( field.pentahoFieldName ) ) {
      return;
    }
    consumer.startField( field.formatFieldName, index );
    switch ( field.pentahoValueMetaType ) {
      case ValueMetaInterface.TYPE_NUMBER:
        consumer.addDouble( row.getNumber( fieldIndex, Double.parseDouble( field.defaultValue ) ) );
      case ValueMetaInterface.TYPE_STRING:
        consumer.addBinary( Binary.fromString( row.getString( field.pentahoFieldName, field.defaultValue ) ) );
        break;
      case ValueMetaInterface.TYPE_BOOLEAN:
        consumer.addBoolean( row.getBoolean( fieldIndex, Boolean.parseBoolean( field.defaultValue ) ) );
        break;
      case ValueMetaInterface.TYPE_INTEGER:
        consumer.addLong( row.getInteger( fieldIndex, Long.parseLong( field.defaultValue ) ) );
        break;
      case ValueMetaInterface.TYPE_BIGNUMBER:
        consumer.addDouble( row.getNumber( fieldIndex, Double.parseDouble( field.defaultValue ) ) );
        break;
      case ValueMetaInterface.TYPE_SERIALIZABLE:
        /**
         * 'fromByteArray' deprecated in the HDP, but CDH doesn't have 'fromReusedByteArray' yet.
         */
//#if shim_type=="HDP" || shim_type=="EMR" || shim_type=="HDI"
        consumer.addBinary( Binary.fromReusedByteArray( row.getBinary( fieldIndex, new byte[0] ) ) );
//#endif
//$     consumer.addBinary( Binary.fromByteArray( row.getBinary( fieldIndex, new byte[0] ) ) );
//#if shim_type=="CDH" || shim_type=="MAPR"
//#endif
        break;
      case ValueMetaInterface.TYPE_BINARY:
        /**
         * 'fromByteArray' deprecated in the HDP, but CDH doesn't have 'fromReusedByteArray' yet.
         */
//#if shim_type=="HDP" || shim_type=="EMR" || shim_type=="HDI"
        consumer.addBinary( Binary.fromReusedByteArray( row.getBinary( fieldIndex, new byte[0] ) ) );
//#endif
//$     consumer.addBinary( Binary.fromByteArray( row.getBinary( fieldIndex, new byte[0] ) ) );
//#if shim_type=="CDH" || shim_type=="MAPR"
//#endif
        break;
      default:
        throw new RuntimeException( "Undefined type: " + field.pentahoValueMetaType );
    }
    consumer.endField( field.formatFieldName, index );
  }

  public void writeRow( RowMetaAndData row, RecordConsumer consumer ) {
    consumer.startMessage();
    int index = 0;
    for ( SchemaDescription.Field f : schema ) {
      if ( f.formatFieldName == null ) {
        continue;
      }
      try {
        writeField( f, index, row, consumer );
        index++;
      } catch ( KettleValueException ex ) {
        throw new RuntimeException( ex );
      }
    }
    consumer.endMessage();
  }

  public RowMetaAndData readRow( RecordReader<Void, RowMetaAndData> reader ) throws IOException, InterruptedException {
    RowMeta rowMeta = new RowMeta();
    List<Object> data = new ArrayList<>();

    while ( reader.nextKeyValue() ) {
      Object o = reader.getCurrentValue();
      System.out.println( o );
    }

    return new RowMetaAndData( rowMeta, data.toArray( new Object[data.size()] ) );
  }

  public static class MyRecordMaterializer extends RecordMaterializer<RowMetaAndData> {
    private MyGroupConverter root;

    public MyRecordMaterializer( ParquetConverter converter, SchemaDescription schema ) {
      root = new MyGroupConverter( converter, schema );
    }

    @Override
    public RowMetaAndData getCurrentRecord() {
      return root.getCurrentRecord();
    }

    @Override
    public GroupConverter getRootConverter() {
      return root;
    }
  }

  public static class MyGroupConverter extends GroupConverter {
    private RowMeta fields = new RowMeta();
    protected RowMetaAndData current;
    private Converter[] converters;
    private int count;

    public MyGroupConverter( ParquetConverter converter, SchemaDescription schema ) {
      count = 0;
      for ( SchemaDescription.Field f : converter.schema ) {
        if ( f.formatFieldName != null ) {
          count++;
        }
      }
      converters = new Converter[count];
      int i = 0;
      for ( SchemaDescription.Field f : converter.schema ) {
        if ( f.formatFieldName == null ) {
          continue;
        }

        final int index = i;
        switch ( f.pentahoValueMetaType ) {
          case ValueMetaInterface.TYPE_NUMBER:
            fields.addValueMeta( new ValueMetaNumber( f.pentahoFieldName ) );
            converters[i] = new PrimitiveConverter() {
              @Override
              public void addDouble( double value ) {
                current.getData()[index] = value;
              }

              @Override
              public void addFloat( float value ) {
                current.getData()[index] = value;
              }

              @Override
              public void addInt( int value ) {
                current.getData()[index] = value;
              }

              @Override
              public void addLong( long value ) {
                current.getData()[index] = value;
              }
            };
            break;
          case ValueMetaInterface.TYPE_INTEGER:
            fields.addValueMeta( new ValueMetaInteger( f.pentahoFieldName ) );
            converters[i] = new PrimitiveConverter() {
              @Override
              public void addDouble( double value ) {
                current.getData()[index] = value;
              }

              @Override
              public void addFloat( float value ) {
                current.getData()[index] = value;
              }

              @Override
              public void addInt( int value ) {
                current.getData()[index] = value;
              }

              @Override
              public void addLong( long value ) {
                current.getData()[index] = value;
              }
            };
            break;
          case ValueMetaInterface.TYPE_BIGNUMBER:
            fields.addValueMeta( new ValueMetaBigNumber( f.pentahoFieldName ) );
            converters[i] = new PrimitiveConverter() {
              @Override
              public void addDouble( double value ) {
                current.getData()[index] = value;
              }

              @Override
              public void addFloat( float value ) {
                current.getData()[index] = value;
              }

              @Override
              public void addInt( int value ) {
                current.getData()[index] = value;
              }

              @Override
              public void addLong( long value ) {
                current.getData()[index] = value;
              }
            };
            break;
          case ValueMetaInterface.TYPE_STRING:
            fields.addValueMeta( new ValueMetaString( f.pentahoFieldName ) );
            converters[i] = new PrimitiveConverter() {
              @Override
              public void addBinary( Binary value ) {
                current.getData()[index] = value.toStringUsingUTF8();
              }
            };
            break;
          case ValueMetaInterface.TYPE_BOOLEAN:
            fields.addValueMeta( new ValueMetaBoolean( f.pentahoFieldName ) );
            converters[i] = new PrimitiveConverter() {
              @Override
              public void addBoolean( boolean value ) {
                current.getData()[index] = value;
              }
            };
            break;
          case ValueMetaInterface.TYPE_SERIALIZABLE:
            fields.addValueMeta( new ValueMetaSerializable( f.pentahoFieldName ) );
            converters[i] = new PrimitiveConverter() {
              @Override
              public void addBinary( Binary value ) {
                current.getData()[index] = value.getBytes();
              }
            };
            break;
          case ValueMetaInterface.TYPE_BINARY:
            fields.addValueMeta( new ValueMetaBinary( f.pentahoFieldName ) );
            converters[i] = new PrimitiveConverter() {
              @Override
              public void addBinary( Binary value ) {
                current.getData()[index] = value.getBytes();
              }
            };
            break;
          default:
            throw new RuntimeException( "Undefined type: " + f.pentahoValueMetaType );
        }
        i++;
      }
    }

    @Override
    public void start() {
      current = new RowMetaAndData( fields );
      current.setData( new Object[count] );
    }

    @Override
    public Converter getConverter( int fieldIndex ) {
      return converters[fieldIndex];
    }

    @Override
    public void end() {
    }

    public RowMetaAndData getCurrentRecord() {
      return current;
    }
  }
}
