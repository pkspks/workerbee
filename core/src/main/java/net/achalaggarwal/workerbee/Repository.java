package net.achalaggarwal.workerbee;

import net.achalaggarwal.workerbee.TextTable.Dual;
import net.achalaggarwal.workerbee.ddl.create.DatabaseCreator;
import net.achalaggarwal.workerbee.ddl.create.TextTableCreator;
import net.achalaggarwal.workerbee.ddl.misc.LoadData;
import net.achalaggarwal.workerbee.ddl.misc.TruncateTable;
import net.achalaggarwal.workerbee.dml.insert.InsertQuery;
import net.achalaggarwal.workerbee.dr.SelectQuery;
import net.achalaggarwal.workerbee.dr.selectfunction.Constant;
import net.achalaggarwal.workerbee.expression.BooleanExpression;
import org.apache.avro.specific.SpecificRecord;
import org.apache.commons.io.FileUtils;
import org.apache.hadoop.conf.Configuration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

import static net.achalaggarwal.workerbee.Database.DEFAULT;
import static net.achalaggarwal.workerbee.QueryGenerator.*;
import static net.achalaggarwal.workerbee.QueryGenerator.select;
import static java.lang.String.valueOf;
import static net.achalaggarwal.workerbee.Utils.*;
import static net.achalaggarwal.workerbee.dr.SelectFunctionGenerator.star;
import static net.achalaggarwal.workerbee.expression.BooleanExpression.EQUALS;

public class Repository implements AutoCloseable {
  private static final String DRIVER_NAME = "org.apache.hive.jdbc.HiveDriver";
  private static final String JDBC_HIVE2_EMBEDDED_MODE_URL = "jdbc:hive2://";

  public static final Path ROOT_DIR = Paths.get("/", "tmp", "workerbee", valueOf(getRandomPositiveNumber()));

  private Map<String, String> hiveVarMap = new HashMap<>();

  private static Logger LOGGER = Logger.getLogger(Repository.class.getName());

  private Connection connection;
  private FSOperation fso;

  public static Repository TemporaryRepository() throws IOException, SQLException {
    return TemporaryRepository(ROOT_DIR);
  }

  public static Repository TemporaryRepository(Path rootDir) throws IOException, SQLException {
    LOGGER.info("Initializing repository at : " + rootDir);
    return new Repository(
      JDBC_HIVE2_EMBEDDED_MODE_URL,
      getHiveConfiguration(rootDir)
    );
  }

  public Repository(String connectionUrl, Properties properties) throws SQLException, IOException {
    this(connectionUrl, properties, new Configuration());
  }

  public Repository(String connectionUrl, Properties properties, Configuration conf) throws SQLException, IOException {
    try {
      Class.forName(DRIVER_NAME);
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
      System.exit(1);
    }

    LOGGER.info("Connecting to : " + connectionUrl);
    connection = DriverManager.getConnection(connectionUrl, properties);
    fso = new FSOperation(conf);

    execute(new DatabaseCreator(DEFAULT).ifNotExist().generate());
    create(Dual.tb);
    load(Dual.tb, Arrays.asList(new Row<>(Dual.tb, "X")));
    hiveVar(AvroTable.AVRO_SCHEMA_URL_PATH, fso.getAvroSchemaBasePath());
  }

  public Repository create(TextTable table) throws SQLException, IOException {
    execute(table.create().ifNotExist().generate());
    clear(table);

    return this;
  }

  public Repository create(AvroTable table) throws SQLException, IOException {
    fso.writeTableSchema(table);
    execute(table.create().ifNotExist().generate());
    clear(table);

    return this;
  }

  public <T extends TextTable>  Repository load(T table, List<Row<T>> rows) throws SQLException, IOException {
    for (Row<T> row : rows) {
      execute(new LoadData()
        .data(row)
        .from(fso.writeTextRow(table, row).toString())
        .into(table)
      );
    }
    return this;
  }

  public <T extends AvroTable>  Repository load(T table, List<Row<T>> rows) throws SQLException, IOException {
    for (Row<T> row : rows) {
      execute(new LoadData()
        .data(row)
        .from(fso.writeAvroRow(table, row).toString())
        .into(table)
      );
    }
    return this;
  }

  private Repository clear(Table table) throws SQLException {
    if (table.isExternal()){
      return execute("dfs -rmr " + table.getLocation());
    }

    return execute(new TruncateTable(table).generate());
  }

  public Repository hiveVar(String var, String val) throws SQLException {
    hiveVarMap.put(var, val);
    return execute("SET hivevar:" + var + "=" + val);
  }

  public Repository execute(DatabaseCreator databaseCreator) throws SQLException {
    return execute(databaseCreator.generate());
  }

  public Repository execute(TextTableCreator tableCreator) throws SQLException {
    return execute(tableCreator.generate());
  }

  public Repository execute(InsertQuery insertQuery) throws SQLException {
    return execute(insertQuery.generate());
  }

  public Repository execute(LoadData loadDataQuery) throws SQLException {
    return execute(loadDataQuery.generate());
  }

  public Repository execute(File sqlScriptFile) throws IOException, SQLException {
    return execute(FileUtils.readFileToString(sqlScriptFile));
  }

  public Repository execute(String query) throws SQLException {
    Statement statement = connection.createStatement();

    for (String sqlStatement : query.split("[\\s]*;[\\s]*")) {
      if (sqlStatement.length() > 0) {
        statement.execute(interpolateQuery(sqlStatement));
      }
    }

    return this;
  }

  public ResultSet executeForResult(String query) throws SQLException {
    return connection.createStatement().executeQuery(interpolateQuery(query));
  }

  private String interpolateQuery(String query) {
    LOGGER.info("Executing query [Before interpolation]: " + query);
    String interpolatedStatement = variableSubstituter(query, hiveVarMap);
    LOGGER.info("Executing query [After interpolation]: " + interpolatedStatement);
    return interpolatedStatement;
  }

  public <T extends TextTable> List<Row<T>> execute(SelectQuery selectQuery) throws SQLException {
    Statement statement = connection.createStatement();

    String selectHQL = rtrim(selectQuery.generate());
    LOGGER.info("Executing query : " + selectHQL);

    List<Row<T>> rows = new ArrayList<>();
    ResultSet resultSet = statement.executeQuery(selectHQL);
    while (resultSet.next()) {
      rows.add(new Row<>((T)selectQuery.table(), resultSet));
    }

    return rows;
  }

  public <T extends TextTable> List<Row<T>> unload(T table, Column... partitions) throws SQLException, IOException {
    List<Row<T>> rows = new ArrayList<>();
    for (String record : takeoutRecordsAsString(table, partitions)) {
      rows.add(table.parseRecordUsing(record));
    }

    return rows;
  }

  public <T extends AvroTable> List<Row<T>> unload(T table, Column... partitions) throws SQLException, IOException {
    List<Row<T>> rows = new ArrayList<>();
    for (String record : takeoutRecordsAsString(table, partitions)) {
        rows.add(table.parseRecordUsing(record));
    }

    return rows;
  }

  private List<String> takeoutRecordsAsString(Table table, Column[] partitions) throws SQLException, IOException {

    BooleanExpression expression = new BooleanExpression(new Constant(1), EQUALS, new Constant(1));
    for (Column partition : partitions) {
      expression = expression.and(new BooleanExpression(partition, EQUALS, new Constant(partition.getValue())));
    }

    String tempDirectoryPath = fso.createTempDir();

    execute(recover(table).generate());
    execute(insert().overwrite().directory(tempDirectoryPath)
      .using(select(star()).from(table).where(expression)));

    return fso.readRecords(tempDirectoryPath);
  }

  public <T extends AvroTable, A extends SpecificRecord> List<A> getSpecificRecordsOf(AvroTable<T> table, Column... partitions) throws SQLException, IOException {
    return RowUtils.getSpecificRecords(unload(table, partitions));
  }

  @Override
  public void close() throws SQLException {
    connection.close();
  }

  private static Properties getHiveConfiguration(Path baseDir) {
    final String basePath = baseDir.toAbsolutePath().toString();
    return new Properties(){{
      setProperty("hiveconf:fs.default.name", "file://" + basePath);
      setProperty("hiveconf:mapred.job.tracker", "local");
      setProperty("hiveconf:mapreduce.framework.name", "local");

      setProperty("hiveconf:hive.exec.scratchdir", basePath + "/scratchdir");
      setProperty("hiveconf:hive.querylog.location", basePath + "/querylog");
      setProperty("hiveconf:hive.metastore.warehouse.dir", basePath + "/warehouse");
      setProperty("hiveconf:hive.metastore.local", "true");

      setProperty("hiveconf:javax.jdo.option.ConnectionURL", "jdbc:derby:;databaseName=" + basePath + "/metastore_db;create=true");
      setProperty("hiveconf:javax.jdo.option.ConnectionDriverName", "org.apache.derby.jdbc.EmbeddedDriver");

      setProperty("hiveconf:hive.stats.dbclass", "jdbc:derby");
      setProperty("hiveconf:hive.stats.dbconnectionstring", "jdbc:derby:;databaseName=" + basePath + "/TempStatsStore;create=true");
      setProperty("hiveconf:hive.stats.jdbcdriver", "org.apache.derby.jdbc.EmbeddedDriver");

      setProperty("hiveconf:hive.cli.print.header", "false");
      setProperty("hiveconf:hive.metastore.execute.setugi", "true");
      setProperty("hiveconf:hive.exec.dynamic.partition.mode", "nonstrict");
    }};
  }
}
