import com.google.auto.value.AutoValue;
import org.checkerframework.checker.builder.qual.*;
import org.checkerframework.checker.nullness.qual.*;

import com.google.common.collect.ImmutableList;

@AutoValue
abstract class GuavaImmutablePropBuilder {

  public abstract ImmutableList<String> names();

  static Builder builder() {
    return new AutoValue_GuavaImmutablePropBuilder.Builder();
  }

  @AutoValue.Builder
  abstract static class Builder {

    abstract ImmutableList.Builder<String> namesBuilder();

    abstract GuavaImmutablePropBuilder build();

  }

  public static void buildSomething() {
    // don't need to explicitly set the names
    builder().build();
  }
}