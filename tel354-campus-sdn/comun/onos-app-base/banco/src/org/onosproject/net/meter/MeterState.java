// Doble de prueba del banco de tel354.base: imita lo que la app usa de la API de ONOS 2.7.0.
package org.onosproject.net.meter;

/** Igual que en ONOS 2.7.0: no hay REMOVED (un meter retirado sale del almacén). */
public enum MeterState { PENDING_ADD, ADDED, PENDING_REMOVE }
