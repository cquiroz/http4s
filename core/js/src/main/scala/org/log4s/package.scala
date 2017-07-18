package org

// Poor's man implementation of log4s for scala.js
package object log4s {
  def getLogger: Logger = Logger()
}
