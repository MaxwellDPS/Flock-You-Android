# Flock You - Device makefile include
#
# Include this file in your device.mk or vendor configuration to add
# Flock You as a privileged system app in your GrapheneOS/AOSP build.
#
# Usage in device.mk:
#   $(call inherit-product, vendor/flockyou/flockyou.mk)
#
# Or in BoardConfig.mk:
#   include vendor/flockyou/flockyou.mk
#
# Make sure to copy the built APK (FlockYou.apk) to vendor/flockyou/ first

FLOCKYOU_PATH := vendor/flockyou

# Add FlockYou to the build
PRODUCT_PACKAGES += \
    FlockYou

# Alternatively, for Soong-only builds, you may need:
# PRODUCT_SOONG_NAMESPACES += $(FLOCKYOU_PATH)

# SELinux policy (if custom policies are needed)
# BOARD_SEPOLICY_DIRS += $(FLOCKYOU_PATH)/sepolicy

# Optional: Pre-grant runtime permissions on first boot
# This requires a custom PermissionController or framework modification
# PRODUCT_COPY_FILES += \
#     $(FLOCKYOU_PATH)/default-permissions-flockyou.xml:$(TARGET_COPY_OUT_SYSTEM_EXT)/etc/default-permissions/default-permissions-flockyou.xml
