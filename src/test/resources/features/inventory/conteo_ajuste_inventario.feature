# language: es
@soporte @US41 @T91
Característica: Conteo de validación y ajuste de inventario
  Como encargado de almacén
  quiero realizar un conteo de validación
  para ajustar las existencias cuando exista una diferencia revisada

  Antecedentes:
    Dado que la organización "Constructora Andina SAC" con RUC "20123456789" está registrada con el administrador "ana@andina.pe" y contraseña "Segura123"
    Y que "ana@andina.pe" inició sesión con la contraseña "Segura123"
    Y que registré el almacén "Almacén Central"
    Y que registré el material "Cemento Portland tipo I" con SKU "CEM-001", unidad "bolsa" y tolerancia de merma "2.5" %
    Y que registré el material "Acero corrugado" con SKU "ACE-012", unidad "kilogramo" y tolerancia de merma "1" %
    Y que creé el usuario "Rosa Quispe" con correo "rosa@andina.pe", rol "encargado de almacén" y contraseña "Almacen123"
    Y que asigné a "rosa@andina.pe" al almacén "Almacén Central"
    Y que "rosa@andina.pe" inició sesión con la contraseña "Almacen123"
    Y que el almacén "Almacén Central" tiene "450" del material "CEM-001"
    Y que el almacén "Almacén Central" tiene "20" del material "ACE-012"

  # AC01: al finalizar el reconteo el sistema compara las existencias
  Escenario: Un reconteo que coincide con las existencias se cierra sin ajuste
    Cuando inicio un conteo de validación del almacén "Almacén Central"
    Y registro que conté "450" del material "CEM-001" y "20" del material "ACE-012"
    Y finalizo el conteo
    Entonces el conteo queda en estado "MATCHED" con 0 diferencias

  # AC02: una diferencia revisada se valida y el inventario se actualiza
  Escenario: Una diferencia revisada se ajusta y se publica el ajuste
    Cuando inicio un conteo de validación del almacén "Almacén Central"
    Y registro que conté "445" del material "CEM-001" y "20" del material "ACE-012"
    Y finalizo el conteo
    Entonces el conteo queda en estado "DIFFERENCES_FOUND" con 1 diferencias
    Cuando reviso las diferencias con la nota "Se recontó dos veces; 5 bolsas rotas"
    Y valido el ajuste con el motivo "Rotura de bolsas en el almacén"
    Entonces el conteo queda en estado "ADJUSTED" con 1 diferencias
    Y el stock disponible del material "CEM-001" en el almacén "Almacén Central" es "445"
    Y inventario publicó el ajuste del conteo con la diferencia "-5" del material "CEM-001"
    Y el historial del almacén "Almacén Central" empieza con el movimiento "ADJUSTMENT_OUT"

  Escenario: Una diferencia sin revisar no ajusta el inventario
    Cuando inicio un conteo de validación del almacén "Almacén Central"
    Y registro que conté "440" del material "CEM-001" y "20" del material "ACE-012"
    Y finalizo el conteo
    Y valido el ajuste con el motivo "Rotura"
    Entonces la operación es rechazada con estado 409 y código "COUNT_NOT_REVIEWED"
    Y el stock disponible del material "CEM-001" en el almacén "Almacén Central" es "450"

  Escenario: No se finaliza un conteo con materiales sin contar
    Cuando inicio un conteo de validación del almacén "Almacén Central"
    Y finalizo el conteo
    Entonces la operación es rechazada con estado 409 y código "COUNT_INCOMPLETE"

  Escenario: Un almacén tiene un solo conteo abierto
    Cuando inicio un conteo de validación del almacén "Almacén Central"
    Y inicio un conteo de validación del almacén "Almacén Central"
    Entonces la operación es rechazada con estado 409 y código "COUNT_ALREADY_OPEN"

  Escenario: El encargado de obra no hace conteos de almacén
    Dado que creé el usuario "Juan Pérez" con correo "juan@andina.pe", rol "encargado de obra" y contraseña "Obra1234"
    Y que "juan@andina.pe" inició sesión con la contraseña "Obra1234"
    Cuando inicio un conteo de validación del almacén "Almacén Central"
    Entonces la operación es rechazada con estado 403
