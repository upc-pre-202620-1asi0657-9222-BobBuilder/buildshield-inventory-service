# language: es
@soporte @US40 @T90
Característica: Registro del inventario inicial y de las reposiciones con historial de movimientos
  Como encargado de almacén
  quiero registrar el inventario inicial y las reposiciones de materiales
  para mantener actualizadas las existencias del almacén

  Antecedentes:
    Dado que la organización "Constructora Andina SAC" con RUC "20123456789" está registrada con el administrador "ana@andina.pe" y contraseña "Segura123"
    Y que "ana@andina.pe" inició sesión con la contraseña "Segura123"
    Y que registré el almacén "Almacén Central"
    Y que registré el material "Cemento Portland tipo I" con SKU "CEM-001", unidad "bolsa" y tolerancia de merma "2.5" %
    Y que creé el usuario "Rosa Quispe" con correo "rosa@andina.pe", rol "encargado de almacén" y contraseña "Almacen123"
    Y que asigné a "rosa@andina.pe" al almacén "Almacén Central"
    Y que "rosa@andina.pe" inició sesión con la contraseña "Almacen123"

  # AC01: el conteo de lo recibido se confirma y queda registrado como inventario inicial o reposición
  Escenario: El encargado registra el inventario inicial y después una reposición
    Cuando registro el inventario inicial de "100" del material "CEM-001" en el almacén "Almacén Central"
    Entonces la respuesta tiene estado 201
    Cuando registro una reposición de "40" del material "CEM-001" en el almacén "Almacén Central" con la guía "Guía 001-2345"
    Entonces la respuesta tiene estado 201
    Y el stock disponible del material "CEM-001" en el almacén "Almacén Central" es "140"
    Y el historial del almacén "Almacén Central" muestra los movimientos "REPLENISHMENT, INITIAL"
    Y inventario publicó la entrada "REPLENISHMENT" del material "CEM-001"

  Escenario: El inventario inicial de un material se registra una sola vez
    Dado que el almacén "Almacén Central" tiene "100" del material "CEM-001"
    Cuando registro el inventario inicial de "5" del material "CEM-001" en el almacén "Almacén Central"
    Entonces la operación es rechazada con estado 409 y código "INITIAL_STOCK_ALREADY_REGISTERED"

  Escenario: El historial de movimientos se consulta por páginas y por tipo
    Dado que el almacén "Almacén Central" tiene "100" del material "CEM-001"
    Y que el almacén "Almacén Central" tiene "10" del material "CEM-001"
    Y que el almacén "Almacén Central" tiene "20" del material "CEM-001"
    Cuando consulto la página 0 de tamaño 2 del historial del almacén "Almacén Central"
    Entonces la página tiene 2 movimientos de un total de 3
    Cuando consulto la página 1 de tamaño 2 del historial del almacén "Almacén Central"
    Entonces la página tiene 1 movimientos de un total de 3
    Cuando consulto el historial de movimientos "REPLENISHMENT" del almacén "Almacén Central"
    Entonces la página tiene 2 movimientos de un total de 2

  Escenario: El encargado de obra no registra existencias en el almacén
    Dado que creé el usuario "Juan Pérez" con correo "juan@andina.pe", rol "encargado de obra" y contraseña "Obra1234"
    Y que "juan@andina.pe" inició sesión con la contraseña "Obra1234"
    Cuando registro el inventario inicial de "100" del material "CEM-001" en el almacén "Almacén Central"
    Entonces la operación es rechazada con estado 403
