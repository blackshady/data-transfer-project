package org.dataportabilityproject.worker;

import static com.google.common.collect.MoreCollectors.onlyElement;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import java.util.NoSuchElementException;
import java.util.ServiceLoader;
import org.dataportabilityproject.api.launcher.ExtensionContext;
import org.dataportabilityproject.api.launcher.Logger;
import org.dataportabilityproject.spi.transfer.InMemoryDataCopier;
import org.dataportabilityproject.spi.transfer.extension.TransferExtension;
import org.dataportabilityproject.spi.transfer.provider.Exporter;
import org.dataportabilityproject.spi.transfer.provider.Importer;

final class WorkerModule extends AbstractModule {
  @Override
  protected void configure() {
    bind(InMemoryDataCopier.class).to(PortabilityInMemoryDataCopier.class);
  }

  @Provides
  @Singleton
  ExtensionContext provideExtensionContext() {
    return new ExtensionContext() {
      @Override
      public Logger getLogger() {
        return new Logger() {};
      }

      @Override
      public <T> T getService(Class<T> type) {
        return null;
      }

      @Override
      public <T> T getConfiguration(String key, String defaultValue) {
        return null;
      }
    };
  }

  @Provides
  @Singleton
  Exporter provideExporter(
      ImmutableList<TransferExtension> transferExtensions,
      ExtensionContext extensionContext) {
    TransferExtension extension =
        findTransferExtension(transferExtensions, JobMetadata.getExportService());
    extension.initialize(extensionContext);
    return extension.getExporter(JobMetadata.getDataType());
  }

  @Provides
  @Singleton
  Importer provideImporter(
      ImmutableList<TransferExtension> transferExtensions,
      ExtensionContext extensionContext) {
    TransferExtension extension =
        findTransferExtension(transferExtensions, JobMetadata.getImportService());
    extension.initialize(extensionContext);
    return extension.getImporter(JobMetadata.getDataType());
  }

  private static TransferExtension findTransferExtension(
      ImmutableList<TransferExtension> transferExtensions, String service) {
    try {
      return transferExtensions.stream()
          .filter(ext -> ext.getServiceId().equals(service))
          .collect(onlyElement());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "Found multiple transfer extensions for service " + service, e);
    } catch (NoSuchElementException e) {
      throw new IllegalStateException(
          "Did not find a valid transfer extension for service " + service, e);
    }
  }

  @Provides
  @Singleton
  ImmutableList<TransferExtension> provideTransferExtensions() {
    // TODO: Next version should ideally not load every TransferExtension impl, look into
    // solutions where we selectively invoke class loader.
    ImmutableList.Builder<TransferExtension> extensionsBuilder = ImmutableList.builder();
    ServiceLoader.load(TransferExtension.class).iterator().forEachRemaining(extensionsBuilder::add);
    ImmutableList<TransferExtension> extensions = extensionsBuilder.build();
    Preconditions.checkState(!extensions.isEmpty(),
        "Could not find any implementations of TransferExtension");
    return extensions;
  }
}
