# language: es
@soporte @US43 @T92
Característica: Registro de saldos de obra y devolución al almacén de origen
  Como encargado de obra
  quiero registrar el saldo de material restante y solicitar su devolución
  para retornar al almacén lo que ya no será utilizado

  Antecedentes:
    Dado que la organización "Constructora Andina SAC" con RUC "20123456789" está registrada con el administrador "ana@andina.pe" y contraseña "Segura123"
    Y que "ana@andina.pe" inició sesión con la contraseña "Segura123"
    Y que registré el almacén "Almacén Central"
    Y que registré la obra "Torre Norte"
    Y que registré el material "Cemento Portland tipo I" con SKU "CEM-001", unidad "bolsa" y tolerancia de merma "2.5" %
    Y que creé el usuario "Rosa Quispe" con correo "rosa@andina.pe", rol "encargado de almacén" y contraseña "Almacen123"
    Y que asigné a "rosa@andina.pe" al almacén "Almacén Central"
    Y que creé el usuario "Juan Pérez" con correo "juan@andina.pe", rol "encargado de obra" y contraseña "Obra1234"
    Y que asigné a "juan@andina.pe" a la obra "Torre Norte"
    Y que la obra "Torre Norte" recibió "40" del material "CEM-001" desde el almacén "Almacén Central"

  # AC01 y AC02: el saldo se registra, se solicita, se aprueba, retorna y el almacén reincorpora solo lo aceptado
  Escenario: La devolución aprobada reincorpora al almacén solo lo aceptado
    Dado que "juan@andina.pe" inició sesión con la contraseña "Obra1234"
    Cuando registro un saldo de "12" del material "CEM-001" de la obra "Torre Norte" con el detalle "2 bolsas con humedad"
    Entonces la devolución queda en estado "REGISTERED" hacia el almacén "Almacén Central"
    Cuando solicito la devolución
    Dado que "rosa@andina.pe" inició sesión con la contraseña "Almacen123"
    Cuando apruebo la devolución
    Dado que "juan@andina.pe" inició sesión con la contraseña "Obra1234"
    Cuando inicio el retorno de la devolución
    Entonces el stock de la obra "Torre Norte" del material "CEM-001" es "28"
    Dado que "rosa@andina.pe" inició sesión con la contraseña "Almacen123"
    Cuando verifico que acepto "10" del material "CEM-001" y rechazo el resto por "Bolsas húmedas, no aptas"
    Entonces la devolución queda en estado "RECEIVED" con rechazos
    Y el stock disponible del material "CEM-001" en el almacén "Almacén Central" es "10"
    Y inventario publicó "MaterialReturnReceived" con "2" rechazado del material "CEM-001"

  # AC03: la devolución rechazada queda registrada y el saldo sigue en la obra
  Escenario: El almacén rechaza la devolución y el saldo queda en la obra
    Dado que "juan@andina.pe" inició sesión con la contraseña "Obra1234"
    Cuando registro un saldo de "12" del material "CEM-001" de la obra "Torre Norte" con el detalle "Bolsas cerradas"
    Y solicito la devolución
    Dado que "rosa@andina.pe" inició sesión con la contraseña "Almacen123"
    Cuando rechazo la devolución por "El cemento se usará en la losa del piso 13"
    Entonces la devolución queda en estado "REJECTED" con rechazos
    Y el stock de la obra "Torre Norte" del material "CEM-001" es "40"

  Escenario: No se devuelve más de lo que queda en la obra
    Dado que "juan@andina.pe" inició sesión con la contraseña "Obra1234"
    Cuando registro un saldo de "41" del material "CEM-001" de la obra "Torre Norte" con el detalle "Todo"
    Entonces la operación es rechazada con estado 409 y código "RETURN_EXCEEDS_BALANCE"

  Escenario: El saldo de la obra descuenta lo comprometido en devoluciones pendientes
    Dado que "juan@andina.pe" inició sesión con la contraseña "Obra1234"
    Cuando registro un saldo de "15" del material "CEM-001" de la obra "Torre Norte" con el detalle "Saldo"
    Entonces el saldo de la obra "Torre Norte" permite devolver "25" del material "CEM-001" al almacén "Almacén Central"

  Escenario: El encargado de almacén no registra saldos de obra
    Dado que "rosa@andina.pe" inició sesión con la contraseña "Almacen123"
    Cuando registro un saldo de "5" del material "CEM-001" de la obra "Torre Norte" con el detalle "Saldo"
    Entonces la operación es rechazada con estado 403
