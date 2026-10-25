export const BUS_NAME = 'org.gnome.Shell';
export const BUS_PATH = '/be/julien/QuotaDeck';
export const BUS_INTERFACE = 'be.julien.QuotaDeck';
// No credential getter. Device codes are transient and explicitly fetched by Preferences only.
export const BUS_XML = `<node><interface name="be.julien.QuotaDeck">
  <method name="GetState"><arg type="s" direction="out" name="json"/></method>
  <method name="GetDeviceCode"><arg type="s" direction="out" name="code"/></method>
  <method name="Refresh"><arg type="s" direction="in" name="provider"/></method>
  <method name="Connect"><arg type="s" direction="in" name="provider"/></method>
  <method name="Cancel"><arg type="s" direction="in" name="provider"/></method>
  <method name="Disconnect"><arg type="s" direction="in" name="provider"/></method>
  <method name="SubmitKey"><arg type="s" direction="in" name="key"/></method>
  <signal name="Changed"/>
</interface></node>`;
