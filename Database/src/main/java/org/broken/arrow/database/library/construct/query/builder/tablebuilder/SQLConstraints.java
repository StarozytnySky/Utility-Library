package org.broken.arrow.database.library.construct.query.builder.tablebuilder;

import javax.annotation.Nonnull;

public class SQLConstraints {
  private final String value;

  public SQLConstraints(@Nonnull final String value) {
    this.value = value;
  }

  public static SQLConstraints Null() {
    return new SQLConstraints("NULL");
  }

  public static SQLConstraints Not_null() {
    return new SQLConstraints("NOT NULL");
  }

  public static SQLConstraints Primary_key() {
    return new SQLConstraints("PRIMARY KEY");
  }

  public static SQLConstraints Primary_keys(String... columns) {
    return new SQLConstraints("PRIMARY KEY (" + String.join(", ", columns) + ")");
  }

  public static SQLConstraints Auto_increment() {
    return new SQLConstraints("AUTO_INCREMENT");
  }

  public static SQLConstraints Unique() {
    return new SQLConstraints("UNIQUE");
  }

  public static SQLConstraints Default(String value) {
    return new SQLConstraints("DEFAULT " + value);
  }

  public static SQLConstraints Default(int value) {
    return new SQLConstraints("DEFAULT " + value);
  }

  public static SQLConstraints Default(boolean value) {
    return new SQLConstraints("DEFAULT " + (value ? "TRUE" : "FALSE"));
  }

  public static SQLConstraints Check(String condition) {
    return new SQLConstraints("CHECK(" + condition + ")");
  }

  public static SQLConstraints Foreign_key(String column, String referenceTable, String referenceColumn) {
    return new SQLConstraints("FOREIGN KEY (" + column + ") REFERENCES " + referenceTable + "(" + referenceColumn + ")");
  }

  public static SQLConstraints On_delete_cascade() {
    return new SQLConstraints("ON DELETE CASCADE");
  }

  public static SQLConstraints On_update_cascade() {
    return new SQLConstraints("ON UPDATE CASCADE");
  }

  public static SQLConstraints On_delete_set_nulL() {
    return new SQLConstraints("ON DELETE SET NULL");
  }

  public static SQLConstraints On_update_set_null() {
    return new SQLConstraints("ON UPDATE SET NULL");
  }

  public static SQLConstraints Unique_key(String column) {
    return new SQLConstraints("UNIQUE (" + column + ")");
  }

  public String getValue() {
    return value;
  }

  @Override
  public String toString() {
    return value;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    SQLConstraints that = (SQLConstraints) o;
      return value.equals(that.value);
  }

  public static boolean isPrimary(Object o) {
    if (o == null || SQLConstraints.class != o.getClass()) return false;

    SQLConstraints that = (SQLConstraints) o;
    return that.value.startsWith("PRIMARY KEY");

  }
  @Override
  public int hashCode() {
    return value.hashCode();
  }
}