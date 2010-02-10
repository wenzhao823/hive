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

package org.apache.hadoop.hive.metastore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.AlreadyExistsException;
import org.apache.hadoop.hive.metastore.api.ConfigValSecurityException;
import org.apache.hadoop.hive.metastore.api.Constants;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Index;
import org.apache.hadoop.hive.metastore.api.IndexAlreadyExistsException;
import org.apache.hadoop.hive.metastore.api.InvalidObjectException;
import org.apache.hadoop.hive.metastore.api.InvalidOperationException;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore;
import org.apache.hadoop.hive.metastore.api.Type;
import org.apache.hadoop.hive.metastore.api.UnknownDBException;
import org.apache.hadoop.hive.metastore.api.UnknownTableException;
import org.apache.hadoop.hive.serde2.Deserializer;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.SerDeUtils;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hadoop.util.StringUtils;
import org.apache.thrift.TException;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.server.TServer;
import org.apache.thrift.server.TThreadPoolServer;
import org.apache.thrift.transport.TServerSocket;
import org.apache.thrift.transport.TServerTransport;
import org.apache.thrift.transport.TTransportFactory;

import com.facebook.fb303.FacebookBase;
import com.facebook.fb303.FacebookService;
import com.facebook.fb303.fb_status;

/**
 * TODO:pc remove application logic to a separate interface.
 */
public class HiveMetaStore extends ThriftHiveMetastore {

  public static class HMSHandler extends FacebookBase implements
      ThriftHiveMetastore.Iface {
    public static final Log LOG = LogFactory.getLog(HiveMetaStore.class
        .getName());
    private static boolean createDefaultDB = false;
    private String rawStoreClassName;
    private final HiveConf hiveConf; // stores datastore (jpox) properties,
                                     // right now they come from jpox.properties
    private Warehouse wh; // hdfs warehouse
    private final ThreadLocal<RawStore> threadLocalMS = new ThreadLocal() {
      @Override
      protected synchronized Object initialValue() {
        return null;
      }
    };

    // The next serial number to be assigned
    private boolean checkForDefaultDb;
    private static int nextSerialNum = 0;
    private static ThreadLocal<Integer> threadLocalId = new ThreadLocal() {
      @Override
      protected synchronized Object initialValue() {
        return new Integer(nextSerialNum++);
      }
    };

    public static Integer get() {
      return threadLocalId.get();
    }

    public HMSHandler(String name) throws MetaException {
      super(name);
      hiveConf = new HiveConf(this.getClass());
      init();
    }

    public HMSHandler(String name, HiveConf conf) throws MetaException {
      super(name);
      hiveConf = conf;
      init();
    }

    private ClassLoader classLoader;
    private AlterHandler alterHandler;
    {
      classLoader = Thread.currentThread().getContextClassLoader();
      if (classLoader == null) {
        classLoader = Configuration.class.getClassLoader();
      }
    }

    private boolean init() throws MetaException {
      rawStoreClassName = hiveConf.get("hive.metastore.rawstore.impl");
      checkForDefaultDb = hiveConf.getBoolean(
          "hive.metastore.checkForDefaultDb", true);
      String alterHandlerName = hiveConf.get("hive.metastore.alter.impl",
          HiveAlterHandler.class.getName());
      alterHandler = (AlterHandler) ReflectionUtils.newInstance(getClass(
          alterHandlerName, AlterHandler.class), hiveConf);
      wh = new Warehouse(hiveConf);
      createDefaultDB();
      return true;
    }

    /**
     * @return
     * @throws MetaException
     */
    private RawStore getMS() throws MetaException {
      RawStore ms = threadLocalMS.get();
      if (ms == null) {
        LOG.info(threadLocalId.get()
            + ": Opening raw store with implemenation class:"
            + rawStoreClassName);
        ms = (RawStore) ReflectionUtils.newInstance(getClass(rawStoreClassName,
            RawStore.class), hiveConf);
        threadLocalMS.set(ms);
        ms = threadLocalMS.get();
      }
      return ms;
    }

    /**
     * create default database if it doesn't exist
     * 
     * @throws MetaException
     */
    private void createDefaultDB() throws MetaException {
      if (HMSHandler.createDefaultDB || !checkForDefaultDb) {
        return;
      }
      try {
        getMS().getDatabase(MetaStoreUtils.DEFAULT_DATABASE_NAME);
      } catch (NoSuchObjectException e) {
        getMS().createDatabase(
            new Database(MetaStoreUtils.DEFAULT_DATABASE_NAME, wh
                .getDefaultDatabasePath(MetaStoreUtils.DEFAULT_DATABASE_NAME)
                .toString()));
      }
      HMSHandler.createDefaultDB = true;
    }

    private Class<?> getClass(String rawStoreClassName, Class<?> class1)
        throws MetaException {
      try {
        return Class.forName(rawStoreClassName, true, classLoader);
      } catch (ClassNotFoundException e) {
        throw new MetaException(rawStoreClassName + " class not found");
      }
    }

    private void logStartFunction(String m) {
      LOG.info(threadLocalId.get().toString() + ": " + m);
    }

    private void logStartFunction(String f, String db, String tbl) {
      LOG.info(threadLocalId.get().toString() + ": " + f + " : db=" + db
          + " tbl=" + tbl);
    }

    @Override
    public int getStatus() {
      return fb_status.ALIVE;
    }

    public void shutdown() {
      logStartFunction("Shutting down the object store...");
      try {
        if (threadLocalMS.get() != null) {
          getMS().shutdown();
        }
      } catch (MetaException e) {
        LOG.error("unable to shutdown metastore", e);
      }
      System.exit(0);
    }

    public boolean create_database(String name, String location_uri)
        throws AlreadyExistsException, MetaException {
      incrementCounter("create_database");
      logStartFunction("create_database: " + name);
      boolean success = false;
      try {
        getMS().openTransaction();
        Database db = new Database(name, location_uri);
        if (getMS().createDatabase(db)
            && wh.mkdirs(wh.getDefaultDatabasePath(name))) {
          success = getMS().commitTransaction();
        }
      } finally {
        if (!success) {
          getMS().rollbackTransaction();
        }
      }
      return success;
    }

    public Database get_database(String name) throws NoSuchObjectException,
        MetaException {
      incrementCounter("get_database");
      logStartFunction("get_database: " + name);
      return getMS().getDatabase(name);
    }

    public boolean drop_database(String name) throws MetaException {
      incrementCounter("drop_database");
      logStartFunction("drop_database: " + name);
      if (name.equalsIgnoreCase(MetaStoreUtils.DEFAULT_DATABASE_NAME)) {
        throw new MetaException("Can't drop default database");
      }
      boolean success = false;
      try {
        getMS().openTransaction();
        if (getMS().dropDatabase(name)) {
          success = getMS().commitTransaction();
        }
      } finally {
        if (!success) {
          getMS().rollbackTransaction();
        } else {
          wh.deleteDir(wh.getDefaultDatabasePath(name), true);
          // it is not a terrible thing even if the data is not deleted
        }
      }
      return success;
    }

    public List<String> get_databases() throws MetaException {
      incrementCounter("get_databases");
      logStartFunction("get_databases");
      return getMS().getDatabases();
    }

    public boolean create_type(Type type) throws AlreadyExistsException,
        MetaException, InvalidObjectException {
      incrementCounter("create_type");
      logStartFunction("create_type: " + type.getName());
      // check whether type already exists
      if (get_type(type.getName()) != null) {
        throw new AlreadyExistsException("Type " + type.getName()
            + " already exists");
      }

      // TODO:pc Validation of types should be done by clients or here????
      return getMS().createType(type);
    }

    public Type get_type(String name) throws MetaException {
      incrementCounter("get_type");
      logStartFunction("get_type: " + name);
      return getMS().getType(name);
    }

    public boolean drop_type(String name) throws MetaException {
      incrementCounter("drop_type");
      logStartFunction("drop_type: " + name);
      // TODO:pc validate that there are no types that refer to this
      return getMS().dropType(name);
    }

    public Map<String, Type> get_type_all(String name) throws MetaException {
      incrementCounter("get_type_all");
      // TODO Auto-generated method stub
      logStartFunction("get_type_all");
      throw new MetaException("Not yet implemented");
    }

    public void create_table(Table tbl) throws AlreadyExistsException,
        MetaException, InvalidObjectException {
      incrementCounter("create_table");
      logStartFunction("create_table: db=" + tbl.getDbName() + " tbl="
          + tbl.getTableName());

      if (!MetaStoreUtils.validateName(tbl.getTableName())
          || !MetaStoreUtils.validateColNames(tbl.getSd().getCols())
          || (tbl.getPartitionKeys() != null && !MetaStoreUtils
              .validateColNames(tbl.getPartitionKeys()))) {
        throw new InvalidObjectException(tbl.getTableName()
            + " is not a valid object name");
      }

      Path tblPath = null;
      boolean success = false, madeDir = false;
      try {
        getMS().openTransaction();
        if (!TableType.VIRTUAL_VIEW.toString().equals(tbl.getTableType())) {
          if (tbl.getSd().getLocation() == null
            || tbl.getSd().getLocation().isEmpty()) {
            tblPath = wh.getDefaultTablePath(
              tbl.getDbName(), tbl.getTableName());
          } else {
            if (!isExternal(tbl)) {
              LOG.warn("Location: " + tbl.getSd().getLocation()
                + "specified for non-external table:" + tbl.getTableName());
            }
            tblPath = wh.getDnsPath(new Path(tbl.getSd().getLocation()));
          }

          tbl.getSd().setLocation(tblPath.toString());
        }

        // get_table checks whether database exists, it should be moved here
        if (is_table_exists(tbl.getDbName(), tbl.getTableName())) {
          throw new AlreadyExistsException("Table " + tbl.getTableName()
              + " already exists");
        }

        if (tblPath != null) {
          if (!wh.isDir(tblPath)) {
            if (!wh.mkdirs(tblPath)) {
              throw new MetaException(tblPath
                + " is not a directory or unable to create one");
            }
            madeDir = true;
          }
        }

        // set create time
        long time = System.currentTimeMillis() / 1000;
        tbl.setCreateTime((int) time);
        tbl.putToParameters(Constants.DDL_TIME, Long.toString(time));

        getMS().createTable(tbl);
        success = getMS().commitTransaction();

      } finally {
        if (!success) {
          getMS().rollbackTransaction();
          if (madeDir) {
            wh.deleteDir(tblPath, true);
          }
        }
      }
    }

    public boolean is_table_exists(String dbname, String name)
        throws MetaException {
      try {
        return (get_table(dbname, name) != null);
      } catch (NoSuchObjectException e) {
        return false;
      }
    }

    public void drop_table(String dbname, String name, boolean deleteData)
        throws NoSuchObjectException, MetaException {
      incrementCounter("drop_table");
      logStartFunction("drop_table", dbname, name);
      boolean success = false;
      boolean isExternal = false;
      Path tblPath = null;
      Table tbl = null;
      isExternal = false;
      try {
        getMS().openTransaction();
        // drop any partitions
        tbl = get_table(dbname, name);
        if (tbl == null) {
          throw new NoSuchObjectException(name + " doesn't exist");
        }
        if (tbl.getSd() == null) {
          throw new MetaException("Table metadata is corrupted");
        }
        isExternal = isExternal(tbl);
        if (tbl.getSd().getLocation() != null) {
          tblPath = new Path(tbl.getSd().getLocation());
        }
        if (!getMS().dropTable(dbname, name)) {
          throw new MetaException("Unable to drop table");
        }
        tbl = null; // table collections disappear after dropping
        success = getMS().commitTransaction();
      } finally {
        if (!success) {
          getMS().rollbackTransaction();
        } else if (deleteData && (tblPath != null) && !isExternal) {
          wh.deleteDir(tblPath, true);
          // ok even if the data is not deleted
        }
      }
    }

    /**
     * Is this an external table?
     * 
     * @param table
     *          Check if this table is external.
     * @return True if the table is external, otherwise false.
     */
    private boolean isExternal(Table table) {
      if (table == null) {
        return false;
      }
      Map<String, String> params = table.getParameters();
      if (params == null) {
        return false;
      }

      return "TRUE".equalsIgnoreCase(params.get("EXTERNAL"));
    }

    public Table get_table(String dbname, String name) throws MetaException,
        NoSuchObjectException {
      incrementCounter("get_table");
      logStartFunction("get_table", dbname, name);
      Table t = getMS().getTable(dbname, name);
      if (t == null) {
        throw new NoSuchObjectException(dbname + "." + name
            + " table not found");
      }
      return t;
    }

    public boolean set_table_parameters(String dbname, String name,
        Map<String, String> params) throws NoSuchObjectException, MetaException {
      incrementCounter("set_table_parameters");
      logStartFunction("set_table_parameters", dbname, name);
      // TODO Auto-generated method stub
      return false;
    }

    public Partition append_partition(String dbName, String tableName,
        List<String> part_vals) throws InvalidObjectException,
        AlreadyExistsException, MetaException {
      incrementCounter("append_partition");
      logStartFunction("append_partition", dbName, tableName);
      if (LOG.isDebugEnabled()) {
        for (String part : part_vals) {
          LOG.debug(part);
        }
      }
      Partition part = new Partition();
      boolean success = false, madeDir = false;
      Path partLocation = null;
      try {
        getMS().openTransaction();
        part = new Partition();
        part.setDbName(dbName);
        part.setTableName(tableName);
        part.setValues(part_vals);

        Table tbl = getMS().getTable(part.getDbName(), part.getTableName());
        if (tbl == null) {
          throw new InvalidObjectException(
              "Unable to add partition because table or database do not exist");
        }

        part.setSd(tbl.getSd());
        partLocation = new Path(tbl.getSd().getLocation(), Warehouse
            .makePartName(tbl.getPartitionKeys(), part_vals));
        part.getSd().setLocation(partLocation.toString());

        Partition old_part = get_partition(part.getDbName(), part
            .getTableName(), part.getValues());
        if (old_part != null) {
          throw new AlreadyExistsException("Partition already exists:" + part);
        }

        if (!wh.isDir(partLocation)) {
          if (!wh.mkdirs(partLocation)) {
            throw new MetaException(partLocation
                + " is not a directory or unable to create one");
          }
          madeDir = true;
        }

        // set create time
        long time = System.currentTimeMillis() / 1000;
        part.setCreateTime((int) time);
        part.putToParameters(Constants.DDL_TIME, Long.toString(time));

        success = getMS().addPartition(part);
        if (success) {
          success = getMS().commitTransaction();
        }
      } finally {
        if (!success) {
          getMS().rollbackTransaction();
          if (madeDir) {
            wh.deleteDir(partLocation, true);
          }
        }
      }
      return part;
    }

    public int add_partitions(List<Partition> parts) throws MetaException,
        InvalidObjectException, AlreadyExistsException {
      incrementCounter("add_partition");
      if (parts.size() == 0) {
        return 0;
      }
      String db = parts.get(0).getDbName();
      String tbl = parts.get(0).getTableName();
      logStartFunction("add_partitions", db, tbl);
      boolean success = false;
      try {
        getMS().openTransaction();
        for (Partition part : parts) {
          add_partition(part);
        }
        success = true;
        getMS().commitTransaction();
      } finally {
        if (!success) {
          getMS().rollbackTransaction();
        }
      }
      return parts.size();
    }

    public Partition add_partition(Partition part)
        throws InvalidObjectException, AlreadyExistsException, MetaException {
      incrementCounter("add_partition");
      logStartFunction("add_partition", part.getDbName(), part.getTableName());
      boolean success = false, madeDir = false;
      Path partLocation = null;
      try {
        getMS().openTransaction();
        Partition old_part = get_partition(part.getDbName(), part
            .getTableName(), part.getValues());
        if (old_part != null) {
          throw new AlreadyExistsException("Partition already exists:" + part);
        }
        Table tbl = getMS().getTable(part.getDbName(), part.getTableName());
        if (tbl == null) {
          throw new InvalidObjectException(
              "Unable to add partition because table or database do not exist");
        }

        String partLocationStr = part.getSd().getLocation();
        if (partLocationStr == null || partLocationStr.isEmpty()) {
          // set default location if not specified
          partLocation = new Path(tbl.getSd().getLocation(), Warehouse
              .makePartName(tbl.getPartitionKeys(), part.getValues()));

        } else {
          partLocation = wh.getDnsPath(new Path(partLocationStr));
        }

        part.getSd().setLocation(partLocation.toString());

        // Check to see if the directory already exists before calling mkdirs()
        // because if the file system is read-only, mkdirs will throw an  
        // exception even if the directory already exists.
        if (!wh.isDir(partLocation)) {
          if (!wh.mkdirs(partLocation)) {
            throw new MetaException(partLocation
                + " is not a directory or unable to create one");
          }
          madeDir = true;
        }

        // set create time
        long time = System.currentTimeMillis() / 1000;
        part.setCreateTime((int) time);
        part.putToParameters(Constants.DDL_TIME, Long.toString(time));

        success = getMS().addPartition(part) && getMS().commitTransaction();

      } finally {
        if (!success) {
          getMS().rollbackTransaction();
          if (madeDir) {
            wh.deleteDir(partLocation, true);
          }
        }
      }
      return part;
    }

    public boolean drop_partition(String db_name, String tbl_name,
        List<String> part_vals, boolean deleteData)
        throws NoSuchObjectException, MetaException, TException {
      incrementCounter("drop_partition");
      logStartFunction("drop_partition", db_name, tbl_name);
      LOG.info("Partition values:" + part_vals);
      boolean success = false;
      Path partPath = null;
      Table tbl = null;
      try {
        getMS().openTransaction();
        Partition part = get_partition(db_name, tbl_name, part_vals);
        if (part == null) {
          throw new NoSuchObjectException("Partition doesn't exist. "
              + part_vals);
        }
        if (part.getSd() == null || part.getSd().getLocation() == null) {
          throw new MetaException("Partition metadata is corrupted");
        }
        if (!getMS().dropPartition(db_name, tbl_name, part_vals)) {
          throw new MetaException("Unable to drop partition");
        }
        success = getMS().commitTransaction();
        partPath = new Path(part.getSd().getLocation());
        tbl = get_table(db_name, tbl_name);
      } finally {
        if (!success) {
          getMS().rollbackTransaction();
        } else if (deleteData && (partPath != null)) {
          if (tbl != null && !isExternal(tbl)) {
            wh.deleteDir(partPath, true);
            // ok even if the data is not deleted
          }
        }
      }
      return true;
    }

    public Partition get_partition(String db_name, String tbl_name,
        List<String> part_vals) throws MetaException {
      incrementCounter("get_partition");
      logStartFunction("get_partition", db_name, tbl_name);
      return getMS().getPartition(db_name, tbl_name, part_vals);
    }

    public List<Partition> get_partitions(String db_name, String tbl_name,
        short max_parts) throws NoSuchObjectException, MetaException {
      incrementCounter("get_partitions");
      logStartFunction("get_partitions", db_name, tbl_name);
      return getMS().getPartitions(db_name, tbl_name, max_parts);
    }

    public List<String> get_partition_names(String db_name, String tbl_name,
        short max_parts) throws MetaException {
      incrementCounter("get_partition_names");
      logStartFunction("get_partition_names", db_name, tbl_name);
      return getMS().listPartitionNames(db_name, tbl_name, max_parts);
    }

    public void alter_partition(String db_name, String tbl_name,
        Partition new_part) throws InvalidOperationException, MetaException,
        TException {
      incrementCounter("alter_partition");
      logStartFunction("alter_partition", db_name, tbl_name);
      LOG.info("Partition values:" + new_part.getValues());
      try {
        new_part.putToParameters(Constants.DDL_TIME, Long.toString(System
            .currentTimeMillis() / 1000));
        getMS().alterPartition(db_name, tbl_name, new_part);
      } catch (InvalidObjectException e) {
        LOG.error(StringUtils.stringifyException(e));
        throw new InvalidOperationException("alter is not possible");
      }
    }

    public boolean create_index(Index index_def)
        throws IndexAlreadyExistsException, MetaException {
      incrementCounter("create_index");
      // TODO Auto-generated method stub
      throw new MetaException("Not yet implemented");
    }

    public String getVersion() throws TException {
      incrementCounter("getVersion");
      logStartFunction("getVersion");
      return "3.0";
    }

    public void alter_table(String dbname, String name, Table newTable)
        throws InvalidOperationException, MetaException {
      incrementCounter("alter_table");
      logStartFunction("truncate_table: db=" + dbname + " tbl=" + name
          + " newtbl=" + newTable.getTableName());
      newTable.putToParameters(Constants.DDL_TIME, Long.toString(System
          .currentTimeMillis() / 1000));
      alterHandler.alterTable(getMS(), wh, dbname, name, newTable);
    }

    public List<String> get_tables(String dbname, String pattern)
        throws MetaException {
      incrementCounter("get_tables");
      logStartFunction("get_tables: db=" + dbname + " pat=" + pattern);
      return getMS().getTables(dbname, pattern);
    }

    public List<FieldSchema> get_fields(String db, String tableName)
        throws MetaException, UnknownTableException, UnknownDBException {
      incrementCounter("get_fields");
      logStartFunction("get_fields: db=" + db + "tbl=" + tableName);
      String[] names = tableName.split("\\.");
      String base_table_name = names[0];

      Table tbl;
      try {
        tbl = get_table(db, base_table_name);
      } catch (NoSuchObjectException e) {
        throw new UnknownTableException(e.getMessage());
      }
      boolean getColsFromSerDe = SerDeUtils.shouldGetColsFromSerDe(
        tbl.getSd().getSerdeInfo().getSerializationLib());
      if (!getColsFromSerDe) {
        return tbl.getSd().getCols();
      } else {
        try {
          Deserializer s = MetaStoreUtils.getDeserializer(hiveConf, tbl);
          return MetaStoreUtils.getFieldsFromDeserializer(tableName, s);
        } catch (SerDeException e) {
          StringUtils.stringifyException(e);
          throw new MetaException(e.getMessage());
        }
      }
    }

    /**
     * Return the schema of the table. This function includes partition columns
     * in addition to the regular columns.
     * 
     * @param db
     *          Name of the database
     * @param tableName
     *          Name of the table
     * @return List of columns, each column is a FieldSchema structure
     * @throws MetaException
     * @throws UnknownTableException
     * @throws UnknownDBException
     */
    public List<FieldSchema> get_schema(String db, String tableName)
        throws MetaException, UnknownTableException, UnknownDBException {
      incrementCounter("get_schema");
      logStartFunction("get_schema: db=" + db + "tbl=" + tableName);
      String[] names = tableName.split("\\.");
      String base_table_name = names[0];

      Table tbl;
      try {
        tbl = get_table(db, base_table_name);
      } catch (NoSuchObjectException e) {
        throw new UnknownTableException(e.getMessage());
      }
      List<FieldSchema> fieldSchemas = get_fields(db, base_table_name);

      if (tbl == null || fieldSchemas == null) {
        throw new UnknownTableException(tableName + " doesn't exist");
      }

      if (tbl.getPartitionKeys() != null) {
        // Combine the column field schemas and the partition keys to create the
        // whole schema
        fieldSchemas.addAll(tbl.getPartitionKeys());
      }
      return fieldSchemas;
    }

    public String getCpuProfile(int profileDurationInSec) throws TException {
      return "";
    }

    /**
     * Returns the value of the given configuration variable name. If the
     * configuration variable with the given name doesn't exist, or if there
     * were an exception thrown while retrieving the variable, or if name is
     * null, defaultValue is returned.
     */
    public String get_config_value(String name, String defaultValue)
        throws TException, ConfigValSecurityException {
      incrementCounter("get_config_value");
      logStartFunction("get_config_value: name=" + name + " defaultValue="
          + defaultValue);
      if (name == null) {
        return defaultValue;
      }
      // Allow only keys that start with hive.*, hdfs.*, mapred.* for security
      // i.e. don't allow access to db password
      if (!Pattern.matches("(hive|hdfs|mapred).*", name)) {
        throw new ConfigValSecurityException("For security reasons, the "
            + "config key " + name + " cannot be accessed");
      }

      String toReturn = defaultValue;
      try {
        toReturn = hiveConf.get(name, defaultValue);
      } catch (RuntimeException e) {
        LOG.error(threadLocalId.get().toString() + ": "
            + "RuntimeException thrown in get_config_value - msg: "
            + e.getMessage() + " cause: " + e.getCause());
      }
      return toReturn;
    }

    public Partition get_partition_by_name(String db_name, String tbl_name,
        String part_name) throws MetaException, UnknownTableException, NoSuchObjectException, TException {
      incrementCounter("get_partition_by_name");
      logStartFunction("get_partition_by_name: db=" + db_name + " tbl_name="
          + tbl_name + " part_name=" + part_name);
     
      // Unescape the partition name
      LinkedHashMap<String, String> hm = Warehouse.makeSpecFromName(part_name);
      
      // getPartition expects partition values in a list. use info from the
      // table to put the partition column values in order
      Table t = getMS().getTable(db_name, tbl_name);
      if (t == null) {
        throw new UnknownTableException(db_name + "." + tbl_name
            + " table not found");
      }
      
      List<String> partVals = new ArrayList<String>();
      for(FieldSchema field : t.getPartitionKeys()) {
        String key = field.getName();
        String val = hm.get(key);
        if(val == null) {
          throw new NoSuchObjectException("incomplete partition name - missing " + key);
        }
        partVals.add(val);
      }
      Partition p = getMS().getPartition(db_name, tbl_name, partVals);
      
      if(p == null) {
        throw new NoSuchObjectException(db_name + "." + tbl_name
            + " partition (" + part_name + ") not found");
      }
      return p;
    }
  }

  /**
   * @param args
   */
  public static void main(String[] args) {
    int port = 9083;

    if (args.length > 0) {
      port = Integer.getInteger(args[0]);
    }
    try {
      TServerTransport serverTransport = new TServerSocket(port);
      Iface handler = new HMSHandler("new db based metaserver");
      FacebookService.Processor processor = new ThriftHiveMetastore.Processor(
          handler);
      TThreadPoolServer.Options options = new TThreadPoolServer.Options();
      options.minWorkerThreads = 200;
      TServer server = new TThreadPoolServer(processor, serverTransport,
          new TTransportFactory(), new TTransportFactory(),
          new TBinaryProtocol.Factory(), new TBinaryProtocol.Factory(), options);
      HMSHandler.LOG.info("Started the new metaserver on port [" + port
          + "]...");
      HMSHandler.LOG.info("Options.minWorkerThreads = "
          + options.minWorkerThreads);
      HMSHandler.LOG.info("Options.maxWorkerThreads = "
          + options.maxWorkerThreads);
      server.serve();
    } catch (Throwable x) {
      x.printStackTrace();
      HMSHandler.LOG
          .error("Metastore Thrift Server threw an exception. Exiting...");
      HMSHandler.LOG.error(StringUtils.stringifyException(x));
      System.exit(1);
    }
  }
}
