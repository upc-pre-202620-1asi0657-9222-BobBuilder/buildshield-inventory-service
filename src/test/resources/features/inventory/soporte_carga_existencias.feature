# language: es
@soporte
Característica: Carga y consulta de existencias por almacén
  Como encargado de almacén
  quiero registrar las entradas de material a mi almacén
  para que los pedidos se atiendan con el stock real

  Antecedentes:
    Dado que la organización "Constructora Andina SAC" con RUC "20123456789" está registrada con el administrador "ana@andina.pe" y contraseña "Segura123"
    Y que "ana@andina.pe" inició sesión con la contraseña "Segura123"
    Y que registré el almacén "Almacén Central"
    Y que registré el almacén "Almacén Callao"
    Y que registré el material "Cemento Portland tipo I" con SKU "CEM-001", unidad "bolsa" y tolerancia de merma "2.5" %
    Y que creé el usuario "Rosa Quispe" con correo "rosa@andina.pe", rol "encargado de almacén" y contraseña "Almacen123"
    Y que asigné a "rosa@andina.pe" al almacén "Almacén Central"

  Escenario: El encargado registra una entrada y aumenta lo disponible
    Dado que "rosa@andina.pe" inició sesión con la contraseña "Almacen123"
    Cuando registro una entrada de "100" del material "CEM-001" en el almacén "Almacén Central"
    Entonces la respuesta tiene estado 201
    Cuando registro una entrada de "20.5" del material "CEM-001" en el almacén "Almacén Central"
    Entonces el stock disponible del material "CEM-001" en el almacén "Almacén Central" es "120.5"

  Escenario: La cantidad de la entrada debe ser mayor que cero
    Cuando registro una entrada de "0" del material "CEM-001" en el almacén "Almacén Central"
    Entonces la operación es rechazada con estado 400

  Escenario: El encargado no carga stock en un almacén que no tiene asignado
    Dado que "rosa@andina.pe" inició sesión con la contraseña "Almacen123"
    Cuando registro una entrada de "10" del material "CEM-001" en el almacén "Almacén Callao"
    Entonces la operación es rechazada con estado 404

  Escenario: Cada encargado consulta el stock de sus almacenes
    Dado que el almacén "Almacén Central" tiene "100" del material "CEM-001"
    Y que el almacén "Almacén Callao" tiene "50" del material "CEM-001"
    Y que "rosa@andina.pe" inició sesión con la contraseña "Almacen123"
    Cuando consulto el stock
    Entonces el stock listado es solo del almacén "Almacén Central"

  Escenario: No se carga stock en un almacén desactivado
    Dado que desactivé el almacén "Almacén Callao"
    Cuando registro una entrada de "10" del material "CEM-001" en el almacén "Almacén Callao"
    Entonces la operación es rechazada con estado 409 y código "SITE_INACTIVE"

  Escenario: No se carga stock de un material retirado del catálogo
    Dado que retiré del catálogo el material "CEM-001"
    Cuando registro una entrada de "10" del material "CEM-001" en el almacén "Almacén Central"
    Entonces la operación es rechazada con estado 409 y código "MATERIAL_INACTIVE"

  Escenario: El encargado de obra ve el stock de los almacenes activos
    Dado que el almacén "Almacén Central" tiene "100" del material "CEM-001"
    Y que desactivé el almacén "Almacén Callao"
    Y que creé el usuario "Juan Pérez" con correo "juan@andina.pe", rol "encargado de obra" y contraseña "Obra1234"
    Y que "juan@andina.pe" inició sesión con la contraseña "Obra1234"
    Cuando consulto el stock
    Entonces el stock listado es solo del almacén "Almacén Central"
