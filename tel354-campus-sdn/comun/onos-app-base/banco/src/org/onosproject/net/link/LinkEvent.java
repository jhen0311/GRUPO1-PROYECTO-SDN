// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.link;

import org.onosproject.net.Link;

public class LinkEvent implements org.onosproject.event.Event<LinkEvent.Type, Link> {
    public enum Type { LINK_ADDED, LINK_UPDATED, LINK_REMOVED }
    private final Type t;
    private final Link l;
    public LinkEvent(Type t, Link l) { this.t = t; this.l = l; }
    public Type type() { return t; }
    public Link subject() { return l; }
    public long time() { return System.currentTimeMillis(); }
}
