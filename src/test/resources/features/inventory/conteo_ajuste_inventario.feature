# language: es
@soporte @US41 @T91
Característica: Conteo físico y ajuste de inventario
  Como encargado de almacén
  quiero registrar un conteo físico
  para ajustar las existencias cuando exista una diferencia

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

  # AC01: el sistema compara lo contado con las existencias
  Escenario: Un conteo que coincide con las existencias queda como constancia sin ajuste
    Cuando comparo lo contado en el almacén "Almacén Central": "450" del material "CEM-001" y "20" del material "ACE-012"
    Entonces el conteo queda en estado "MATCHED" con 0 diferencias
    Y el almacén "Almacén Central" no tiene ajustes guardados
    Cuando registro lo contado en el almacén "Almacén Central": "450" del material "CEM-001" y "20" del material "ACE-012"
    Entonces el conteo queda en estado "MATCHED" con 0 diferencias
    Y el almacén "Almacén Central" tiene 2 ajustes guardados y el del material "CEM-001" dice contado "450", sistema "450"

  # AC02: una diferencia se valida con su motivo y el inventario se actualiza
  Escenario: Una diferencia con motivo se ajusta y se publica el ajuste
    Cuando comparo lo contado en el almacén "Almacén Central": "445" del material "CEM-001" y "20" del material "ACE-012"
    Entonces el conteo queda en estado "DIFFERENCES_FOUND" con 1 diferencias
    Y el stock disponible del material "CEM-001" en el almacén "Almacén Central" es "450"
    Cuando ajusto lo contado en el almacén "Almacén Central": "445" del material "CEM-001" y "20" del material "ACE-012" con el motivo "Rotura de bolsas en el almacén"
    Entonces el conteo queda en estado "ADJUSTED" con 1 diferencias
    Y el stock disponible del material "CEM-001" en el almacén "Almacén Central" es "445"
    Y inventario publicó el ajuste del conteo con la diferencia "-5" del material "CEM-001"
    Y el historial del almacén "Almacén Central" empieza con el movimiento "ADJUSTMENT_OUT"
    Y el almacén "Almacén Central" tiene 2 ajustes guardados y el del material "CEM-001" dice contado "445", sistema "450"

  Escenario: Una diferencia sin motivo no ajusta el inventario
    Cuando registro lo contado en el almacén "Almacén Central": "440" del material "CEM-001" y "20" del material "ACE-012"
    Entonces la operación es rechazada con estado 400 y código "ADJUSTMENT_REASON_REQUIRED"
    Y el stock disponible del material "CEM-001" en el almacén "Almacén Central" es "450"
    Y el almacén "Almacén Central" no tiene ajustes guardados

  Escenario: El encargado de obra no hace conteos de almacén
    Dado que creé el usuario "Juan Pérez" con correo "juan@andina.pe", rol "encargado de obra" y contraseña "Obra1234"
    Y que "juan@andina.pe" inició sesión con la contraseña "Obra1234"
    Cuando comparo lo contado en el almacén "Almacén Central": "450" del material "CEM-001" y "20" del material "ACE-012"
    Entonces la operación es rechazada con estado 403
