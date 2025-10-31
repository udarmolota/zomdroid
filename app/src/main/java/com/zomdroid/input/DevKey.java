package com.zomdroid.input;

import android.view.InputDevice;
import androidx.annotation.NonNull;
import java.util.Objects;

/**
 * Unique identifier for an input device (keyboard, gamepad, etc.).
 * Based on descriptor, vendorId, and productId.
 */
public final class DevKey {
  public final String descriptor;
  public final int vendorId;
  public final int productId;
  public final String name;

  public DevKey(@NonNull InputDevice device) {
    this.descriptor = device.getDescriptor();
    this.vendorId = device.getVendorId();
    this.productId = device.getProductId();
    this.name = device.getName();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof DevKey)) return false;
    DevKey other = (DevKey) o;

    // Compare by descriptor (it's more stable); if not available, fall back to vendorId + productId + name
    if (descriptor != null && other.descriptor != null)
      return descriptor.equals(other.descriptor);

    return vendorId == other.vendorId && productId == other.productId
      && Objects.equals(name, other.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(descriptor != null ? descriptor : name, vendorId, productId);
  }

  @Override
  public String toString() {
    return "DevKey{" +
      "desc='" + descriptor + '\'' +
      ", vendor=" + vendorId +
      ", product=" + productId +
      ", name='" + name + '\'' +
      '}';
  }
}

