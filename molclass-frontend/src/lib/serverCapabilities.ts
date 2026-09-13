export const serverConfigurationEnabled =
  process.env.NEXT_PUBLIC_MOLCLASS_CONFIGURATION_ENABLED !== "false";

export const serverConfigurationDisabledMessage =
  "This is not enabled on this server.";

export function showServerConfigurationDisabled() {
  window.alert(serverConfigurationDisabledMessage);
}
