package com.hw1.persistence;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javassist.util.proxy.MethodHandler;
import javassist.util.proxy.ProxyFactory;

public class SQLiteDB {
    private Connection connection;
    private SQLiteDBHelper dbHelper;
    
    public SQLiteDB(String dbPath) throws SQLException {
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath);
        this.dbHelper = new SQLiteDBHelper(this.connection);
    }
    
    public void createTable(Class<?> clazz) throws SQLException {
        
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sql.append(clazz.getSimpleName()).append(" (");
        
        Field[] fields = clazz.getDeclaredFields();
        List<Field> persistableFields = new ArrayList<>();
        
        // Only include fields marked with @Persistable annotation
        for (Field field : fields) {
            if (field.isAnnotationPresent(Persistable.class)) {
                persistableFields.add(field);
            }
        }
        
        for (int i = 0; i < persistableFields.size(); i++) {
            Field field = persistableFields.get(i);
            String fieldName = dbHelper.camelToSnakeCase(field.getName());
            String sqlType = dbHelper.getSQLType(field.getType());
            
            sql.append(fieldName).append(" ").append(sqlType);
            
            // Check if field is marked as PrimaryKey
            if (field.isAnnotationPresent(PrimaryKey.class)) {
                sql.append(" PRIMARY KEY");
            }
            
            if (i < persistableFields.size() - 1) {
                sql.append(", ");
            }
        }
        
        sql.append(")");
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql.toString());
        }
    }
    

    public void droptTable(Class<?> clazz) throws SQLException {
        String sql = "DROP TABLE IF EXISTS " + clazz.getSimpleName();
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }   
    
    /**
     * Insert the given object into the database using reflection.
     * Only fields annotated with @Persistable should be stored.
     * Check the handout for more details.
     */
    public void insertRow(Object obj) throws SQLException, IllegalAccessException {
        Class<?> clazz = obj.getClass();
        Field[] fields = clazz.getDeclaredFields();

        // Collect all @Persistable fields
        List<Field> persistableFields = new ArrayList<>();
        for (Field field : fields) {
            if (field.isAnnotationPresent(Persistable.class)) {
                persistableFields.add(field);
            }
        }

        // Build the INSERT SQL query
        StringBuilder sql = new StringBuilder("INSERT INTO ");
        sql.append(clazz.getSimpleName()).append(" (");

        // Add column names
        for (int i = 0; i < persistableFields.size(); i++) {
            Field field = persistableFields.get(i);
            sql.append(dbHelper.camelToSnakeCase(field.getName()));
            if (i < persistableFields.size() - 1) {
                sql.append(", ");
            }
        }

        sql.append(") VALUES (");

        // Add placeholders
        for (int i = 0; i < persistableFields.size(); i++) {
            sql.append("?");
            if (i < persistableFields.size() - 1) {
                sql.append(", ");
            }
        }

        sql.append(")");

        // Execute the query
        try (PreparedStatement pstmt = connection.prepareStatement(sql.toString())) {
            // Set values for each field
            for (int i = 0; i < persistableFields.size(); i++) {
                Field field = persistableFields.get(i);
                field.setAccessible(true);
                Object value = field.get(obj);

                // Handle different types
                if (field.getType() == String.class) {
                    pstmt.setString(i + 1, (String) value);
                } else if (field.getType() == int.class || field.getType() == Integer.class) {
                    pstmt.setInt(i + 1, (Integer) value);
                } else if (field.getType() == byte[].class) {
                    pstmt.setBytes(i + 1, (byte[]) value);
                }
            }

            pstmt.executeUpdate();
        }
    }

    /**
     * Load a row from the database using reflection.
     * The object passed in should have its primary key field populated.
     * This method will load the other persistable fields from the database
     * and return a proxy object that lazy-loads fields annotated with @RemoteLazyLoad.
     * Check the handout for more details.
     *
     */
    @SuppressWarnings("unchecked")
    public <T> T loadRow(T obj) throws SQLException, IllegalAccessException, NoSuchMethodException, InstantiationException, java.lang.reflect.InvocationTargetException {
        Class<?> clazz = obj.getClass();
        Field[] fields = clazz.getDeclaredFields();

        // Find the primary key field and its value
        Field primaryKeyField = null;
        Object primaryKeyValue = null;
        for (Field field : fields) {
            if (field.isAnnotationPresent(PrimaryKey.class)) {
                primaryKeyField = field;
                field.setAccessible(true);
                primaryKeyValue = field.get(obj);
                break;
            }
        }

        if (primaryKeyField == null || primaryKeyValue == null) {
            System.err.println("No primary key found or primary key is null");
            System.exit(1);
            return null;
        }

        // Collect all @Persistable fields
        List<Field> persistableFields = new ArrayList<>();
        for (Field field : fields) {
            if (field.isAnnotationPresent(Persistable.class)) {
                persistableFields.add(field);
            }
        }

        // Build SELECT query
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < persistableFields.size(); i++) {
            Field field = persistableFields.get(i);
            sql.append(dbHelper.camelToSnakeCase(field.getName()));
            if (i < persistableFields.size() - 1) {
                sql.append(", ");
            }
        }
        sql.append(" FROM ").append(clazz.getSimpleName());
        sql.append(" WHERE ").append(dbHelper.camelToSnakeCase(primaryKeyField.getName())).append(" = ?");

        // Execute query
        try (PreparedStatement pstmt = connection.prepareStatement(sql.toString())) {
            // Set primary key value
            if (primaryKeyField.getType() == String.class) {
                pstmt.setString(1, (String) primaryKeyValue);
            } else if (primaryKeyField.getType() == int.class || primaryKeyField.getType() == Integer.class) {
                pstmt.setInt(1, (Integer) primaryKeyValue);
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    System.err.println("No row found for primary key: " + primaryKeyValue);
                    System.exit(1);
                    return null;
                }

                // Check if any field has @RemoteLazyLoad
                boolean hasLazyLoad = false;
                Set<String> lazyLoadFields = new HashSet<>();
                for (Field field : persistableFields) {
                    if (field.isAnnotationPresent(RemoteLazyLoad.class)) {
                        hasLazyLoad = true;
                        lazyLoadFields.add(field.getName());
                    }
                }

                // Create a new instance to populate
                T resultObj;
                try {
                    resultObj = (T) clazz.getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException("Failed to create instance of " + clazz.getName(), e);
                }

                // Populate fields from result set
                for (Field field : persistableFields) {
                    field.setAccessible(true);
                    String columnName = dbHelper.camelToSnakeCase(field.getName());

                    if (field.getType() == String.class) {
                        field.set(resultObj, rs.getString(columnName));
                    } else if (field.getType() == int.class || field.getType() == Integer.class) {
                        field.set(resultObj, rs.getInt(columnName));
                    } else if (field.getType() == byte[].class) {
                        field.set(resultObj, rs.getBytes(columnName));
                    }
                }

                // If no lazy load fields, return the object as-is
                if (!hasLazyLoad) {
                    return resultObj;
                }

                // Create Javassist proxy for lazy loading
                ProxyFactory factory = new ProxyFactory();
                factory.setSuperclass(clazz);

                MethodHandler handler = new MethodHandler() {
                    @Override
                    public Object invoke(Object self, Method thisMethod, Method proceed, Object[] args) throws Throwable {
                        String methodName = thisMethod.getName();

                        // Check if this is a getter for a @RemoteLazyLoad field
                        if (methodName.startsWith("get") && methodName.length() > 3) {
                            // Convert getter name to field name (e.g., getAuthorAvatar -> authorAvatar)
                            String fieldName = methodName.substring(3, 4).toLowerCase() + methodName.substring(4);

                            // Check if this field is marked for lazy loading
                            if (lazyLoadFields.contains(fieldName)) {
                                // Get the field
                                Field field = null;
                                try {
                                    field = clazz.getDeclaredField(fieldName);
                                    field.setAccessible(true);
                                    Object value = field.get(self);

                                    // Check if value is a byte array that could be a URL
                                    if (value instanceof byte[]) {
                                        byte[] bytes = (byte[]) value;
                                        String potentialUrl = new String(bytes);

                                        // Check if it looks like a URL
                                        if (potentialUrl.startsWith("http://") || potentialUrl.startsWith("https://")) {
                                            // Fetch content from URL
                                            byte[] content = dbHelper.fetchContentFromUrl(potentialUrl);
                                            return content;
                                        }
                                    }

                                    return value;
                                } catch (NoSuchFieldException e) {
                                    // Field doesn't exist, proceed normally
                                }
                            }
                        }

                        // For all other methods, invoke normally
                        return proceed.invoke(self, args);
                    }
                };

                // Create proxy instance
                T proxy = (T) factory.create(new Class<?>[0], new Object[0], handler);

                // Copy all field values from resultObj to proxy
                for (Field field : fields) {
                    field.setAccessible(true);
                    field.set(proxy, field.get(resultObj));
                }

                return proxy;
            }
        }
    }
    
    
    public Connection getConnection() {
        return connection;
    }
    
    public void close() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            connection.close();
        }
    }
}


