# language: es
@saga @US20 @US22 @US37 @US38
Característica: Reserva, salida y recepción de stock en la saga de pedidos
  Como encargado de almacén
  quiero que el stock se reserve al aprobar un pedido y se mueva con cada despacho y recepción
  para que el inventario nunca quede negativo ni se cuente dos veces

  Antecedentes:
    Dado que la organización "Constructora Andina SAC" con RUC "20123456789" está registrada con el administrador "ana@andina.pe" y contraseña "Segura123"
    Y que "ana@andina.pe" inició sesión con la contraseña "Segura123"
    Y que registré el almacén "Almacén Central"
    Y que registré la obra "Torre Norte"
    Y que registré el material "Cemento Portland tipo I" con SKU "CEM-001", unidad "bolsa" y tolerancia de merma "2.5" %
    Y que registré el material "Acero corrugado" con SKU "ACE-012", unidad "kilogramo" y tolerancia de merma "1" %
    Y que el almacén "Almacén Central" tiene "100" del material "CEM-001"

  Escenario: Un pedido aprobado con stock suficiente queda reservado
    Cuando se aprueba el pedido "P-1" de la obra "Torre Norte" al almacén "Almacén Central" con "40" del material "CEM-001"
    Entonces inventario confirma la reserva del pedido "P-1"
    Y el stock reservado del material "CEM-001" en el almacén "Almacén Central" es "40"
    Y el stock disponible del material "CEM-001" en el almacén "Almacén Central" es "60"

  Escenario: Sin stock suficiente no se reserva nada y el rechazo explica lo que falta
    Cuando se aprueba el pedido "P-2" de la obra "Torre Norte" al almacén "Almacén Central" con "150" del material "CEM-001"
    Entonces inventario rechaza la reserva del pedido "P-2" con el motivo "Stock insuficiente en el almacén para CEM-001 (hay 100, se pidió 150)"
    Y el stock reservado del material "CEM-001" en el almacén "Almacén Central" es "0"
    Y el stock disponible del material "CEM-001" en el almacén "Almacén Central" es "100"

  Escenario: Una aprobación entregada dos veces (mismo evento) no reserva dos veces
    Cuando se aprueba el pedido "P-3" de la obra "Torre Norte" al almacén "Almacén Central" con "30" del material "CEM-001"
    Y inventario confirma la reserva del pedido "P-3"
    Y ordering vuelve a enviar la aprobación del pedido "P-3"
    Entonces el pedido "P-3" tiene una sola reserva confirmada una sola vez
    Y el stock reservado del material "CEM-001" en el almacén "Almacén Central" es "30"

  Escenario: La salida del despacho consume la reserva y la obra recibe solo la diferencia
    Cuando se aprueba el pedido "P-4" de la obra "Torre Norte" al almacén "Almacén Central" con "50" del material "CEM-001"
    Y inventario confirma la reserva del pedido "P-4"
    Y parte el despacho "D-1" del pedido "P-4" con "30" del material "CEM-001"
    Entonces inventario informa la salida del despacho "D-1"
    Y el stock reservado del material "CEM-001" en el almacén "Almacén Central" es "20"
    Y el stock disponible del material "CEM-001" en el almacén "Almacén Central" es "50"
    Y la obra "Torre Norte" no tiene stock
    Cuando la obra registra en parcial "20" del material "CEM-001" del despacho "D-1"
    Entonces el stock de la obra "Torre Norte" del material "CEM-001" es "20"
    Cuando la obra confirma la recepción del despacho "D-1" con "9.5" más del material "CEM-001"
    Entonces el stock de la obra "Torre Norte" del material "CEM-001" es "29.5"

  Escenario: Al cerrar el pedido se libera lo reservado que no se despachó
    Cuando se aprueba el pedido "P-5" de la obra "Torre Norte" al almacén "Almacén Central" con "50" del material "CEM-001"
    Y inventario confirma la reserva del pedido "P-5"
    Y parte el despacho "D-2" del pedido "P-5" con "30" del material "CEM-001"
    Y inventario informa la salida del despacho "D-2"
    Y se cierra el pedido "P-5"
    Entonces inventario libera "20" del pedido "P-5"
    Y el stock reservado del material "CEM-001" en el almacén "Almacén Central" es "0"
    Y el stock disponible del material "CEM-001" en el almacén "Almacén Central" es "70"
