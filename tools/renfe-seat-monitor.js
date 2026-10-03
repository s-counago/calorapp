const fs = require("node:fs");
const path = require("node:path");
const { setTimeout: sleep } = require("node:timers/promises");
const { chromium } = require("playwright-core");

const SEARCH_HOME = "https://www.renfe.com/es/es";
const CONFIG_PATH = path.join(__dirname, "renfe-seat-monitor.local.json");
const STATE_PATH = path.join(__dirname, "renfe-seat-monitor.state.json");
const POLL_INTERVAL_MS = 60_000;
function readJson(filePath, fallback) {
  if (!fs.existsSync(filePath)) return fallback;
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

function localDate() {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Europe/Madrid",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date());
  const value = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${value.year}-${value.month}-${value.day}`;
}

function localTime() {
  const parts = new Intl.DateTimeFormat("en-GB", {
    timeZone: "Europe/Madrid",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(new Date());
  const value = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return `${value.hour}:${value.minute}`;
}

function displayDate(value) {
  const [year, month, day] = value.split("-");
  return `${day}/${month}/${year}`;
}

function validateDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  const date = new Date(`${value}T12:00:00Z`);
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === value;
}

function readConfig(args = process.argv.slice(2)) {
  const saved = readJson(CONFIG_PATH, {});
  const config = {
    date: process.env.RENFE_DATE || saved.date || localDate(),
    after: process.env.RENFE_AFTER || saved.after || "20:00",
    departure: process.env.RENFE_DEPARTURE || saved.departure || "",
    originCode: saved.originCode || "23008",
    originName: saved.originName || "VILAGARCÍA DE AROUSA",
    destinationCode: saved.destinationCode || "31400",
    destinationName: saved.destinationName || "SANTIAGO DE COMPOSTELA-DANIEL CASTELAO",
    passengers: Number(process.env.RENFE_PASSENGERS || saved.passengers || 2),
    ntfyTopic: process.env.NTFY_TOPIC || saved.ntfyTopic || "",
    ntfyServer: process.env.NTFY_SERVER || saved.ntfyServer || "https://ntfy.sh",
    ntfyToken: process.env.NTFY_TOKEN || saved.ntfyToken || "",
    chromePath: process.env.CHROME_PATH || saved.chromePath || "",
    once: args.includes("--once"),
    dryRun: args.includes("--dry-run"),
  };
  if (!validateDate(config.date)) throw new Error("Fecha inválida. Usa AAAA-MM-DD.");
  if (config.date < localDate()) throw new Error("La fecha del viaje ya ha pasado.");
  if (!/^([01]\d|2[0-3]):[0-5]\d$/.test(config.after)) {
    throw new Error("Hora inválida. Usa HH:MM.");
  }
  if (config.departure && !/^([01]\d|2[0-3]):[0-5]\d$/.test(config.departure)) {
    throw new Error("Hora de salida inválida. Usa HH:MM.");
  }
  if (
    !/^\d{5}$/.test(config.originCode) ||
    !/^\d{5}$/.test(config.destinationCode) ||
    config.originCode === config.destinationCode ||
    !config.originName ||
    !config.destinationName
  ) {
    throw new Error("Configura estaciones de origen y destino válidas.");
  }
  if (!Number.isInteger(config.passengers) || config.passengers < 1 || config.passengers > 9) {
    throw new Error("El número de viajeros debe estar entre 1 y 9.");
  }
  if (/^https?:\/\//.test(config.ntfyTopic)) {
    const topicUrl = new URL(config.ntfyTopic);
    const parts = topicUrl.pathname.split("/").filter(Boolean);
    if (parts.length !== 1 || topicUrl.search || topicUrl.hash) {
      throw new Error("La URL de ntfy debe apuntar directamente a un topic.");
    }
    config.ntfyServer = topicUrl.origin;
    config.ntfyTopic = decodeURIComponent(parts[0]);
  }
  if (!config.dryRun && !/^[A-Za-z0-9_-]{1,64}$/.test(config.ntfyTopic)) {
    throw new Error("Configura ntfyTopic en tools/renfe-seat-monitor.local.json.");
  }
  const server = new URL(config.ntfyServer);
  if (!["https:", "http:"].includes(server.protocol) || server.username || server.password) {
    throw new Error("ntfyServer debe ser una URL HTTP o HTTPS sin credenciales.");
  }
  config.ntfyServer = server.origin;
  config.chromePath = [
    config.chromePath,
    "C:/Program Files/Google/Chrome/Application/chrome.exe",
    "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
  ].find((candidate) => candidate && fs.existsSync(candidate));
  if (!config.chromePath) throw new Error("No se encontró Chrome. Configura CHROME_PATH.");
  return config;
}

async function openSearch(browser, config) {
  const page = await browser.newPage({ locale: "es-ES", timezoneId: "Europe/Madrid" });
  await page.goto(SEARCH_HOME, { waitUntil: "domcontentloaded", timeout: 30_000 });
  await page.evaluate(({ date, passengers, originCode, originName, destinationCode, destinationName }) => {
    const [year, month, day] = date.split("-");
    const renfeDate = `${day}/${month}/${year}`;
    const fields = {
      tipoBusqueda: "autocomplete",
      currenLocation: "menuBusqueda",
      vengoderenfecom: "SI",
      desOrigen: originName,
      desDestino: destinationName,
      cdgoOrigen: `0071,${originCode},${originCode}`,
      cdgoDestino: `0071,${destinationCode},${destinationCode}`,
      idiomaBusqueda: "ES",
      FechaIdaSel: renfeDate,
      FechaVueltaSel: "",
      _fechaIdaVisual: renfeDate,
      _fechaVueltaVisual: "",
      minPriceDeparture: "false",
      minPriceReturn: "false",
      adultos_: String(passengers),
      ninos_: "0",
      ninosMenores: "0",
      codPromocional: "",
      plazaH: "false",
      sinEnlace: "false",
      conMascota: "false",
      conBicicleta: "false",
      asistencia: "false",
      franjaHoraI: "00:00",
      franjaHoraV: "00:00",
      Idioma: "es",
      Pais: "ES",
    };
    const form = document.createElement("form");
    form.method = "POST";
    form.action = "https://venta.renfe.com/vol/buscarTren.do?Idioma=es&Pais=ES";
    for (const [name, value] of Object.entries(fields)) {
      const input = document.createElement("input");
      input.name = name;
      input.value = value;
      form.append(input);
    }
    document.body.append(form);
    HTMLFormElement.prototype.submit.call(form);
  }, config);
  await page.waitForURL(/\/vol\/buscarTrenEnlaces\.do/, { timeout: 30_000 });
  await page.waitForFunction(() => typeof window.getTrainList === "function", null, {
    timeout: 30_000,
  });
  return page;
}

async function refreshTrains(page, config) {
  if (!page.url().includes("/vol/buscarTrenEnlaces.do")) {
    throw new Error("La sesión de búsqueda de Renfe ha cambiado de página.");
  }
  const adults = await page.evaluate(() => Number(window.filtro?.adultos));
  if (adults !== config.passengers) {
    throw new Error("Renfe ha cambiado el número de viajeros de la búsqueda.");
  }
  await page.evaluate(() => {
    window.__seatMonitorResult = null;
    const original = window.trainEnlacesManager?.getTrainsList;
    if (typeof original !== "function") {
      throw new Error("Renfe no ofrece la consulta de trenes esperada.");
    }
    window.trainEnlacesManager.getTrainsList = function (search, options) {
      window.trainEnlacesManager.getTrainsList = original;
      const callback = options.callback;
      const errorHandler = options.errorHandler;
      options.callback = (data) => {
        window.__seatMonitorResult = { ok: true, data };
        if (callback) callback(data);
      };
      options.errorHandler = (...errors) => {
        window.__seatMonitorResult = { ok: false, error: String(errors[0]) };
        if (errorHandler) errorHandler(...errors);
      };
      return original.call(this, search, options);
    };
    window.getTrainList();
  });
  await page.waitForFunction(() => window.__seatMonitorResult !== null, null, {
    timeout: 30_000,
  });
  const result = await page.evaluate(() => window.__seatMonitorResult);
  if (!result.ok) throw new Error(`Renfe rechazó la consulta: ${result.error}`);
  const journey = result.data?.listadoTrenes?.[0];
  if (!journey || journey.hayError || journey.cdgoError) {
    throw new Error("Renfe no ha devuelto una lista válida de trenes.");
  }
  if (
    journey.codigoEstacionOrigen !== config.originCode ||
    journey.codigoEstacionDestino !== config.destinationCode ||
    journey.fechaOrigen !== displayDate(config.date)
  ) {
    throw new Error("La respuesta de Renfe no coincide con el trayecto solicitado.");
  }
  if (!Array.isArray(journey.listviajeViewEnlaceBean)) {
    if (journey.mensajeListaTrenVacia) return [];
    throw new Error("Renfe no ha devuelto datos de disponibilidad.");
  }
  return journey.listviajeViewEnlaceBean;
}

function offeredTrains(trains, config) {
  return trains
    .filter((train) => {
      if (train.horaSalida <= config.after) return false;
      if (config.departure && train.horaSalida !== config.departure) return false;
      if (config.date === localDate() && train.horaSalida <= localTime()) return false;
      if (train.codigoEstacionOrigen !== config.originCode) return false;
      if (train.codigoEstacionDestino !== config.destinationCode) return false;
      if (train.completo || !["", "8", null, undefined].includes(train.razonNoDisponible)) {
        return false;
      }
      return train.tarifasDisponibles?.some(
        (fare) => !fare.soloPlazasH && fare.precioTarifa != null
      );
    })
    .map((train) => ({
      code:
        train.trayectos?.[0]?.cdgoTren ||
        train.tarifasDisponibles?.[0]?.tarifaTramoCombViewBean?.[0]?.cdgoTren ||
        String(train.id),
      departure: train.horaSalida,
      arrival: train.horaLlegada,
      type: train.trayectos?.[0]?.tipoTren || "Tren",
      price: train.tarifaMinima,
    }));
}

function trainKey(train) {
  return `${train.code}|${train.departure}|${train.arrival}`;
}

function readState(config) {
  const state = readJson(STATE_PATH, null);
  const scope = `${config.date}|${config.originCode}|${config.destinationCode}|${config.after}|${config.departure}|${config.passengers}`;
  if (!state || state.scope !== scope || !Array.isArray(state.available)) {
    return { scope, available: [] };
  }
  return state;
}

function writeState(state) {
  const temporary = `${STATE_PATH}.tmp`;
  fs.writeFileSync(temporary, JSON.stringify(state, null, 2) + "\n");
  fs.renameSync(temporary, STATE_PATH);
}

async function publish(trains, config) {
  const lines = trains.map((train) => {
    const price = train.price ? `, desde ${train.price} €` : "";
    return `${train.departure} → ${train.arrival} (${train.type}${price})`;
  });
  const response = await fetch(config.ntfyServer, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...(config.ntfyToken ? { Authorization: `Bearer ${config.ntfyToken}` } : {}),
    },
    body: JSON.stringify({
      topic: config.ntfyTopic,
      title: config.departure
        ? `Plaza tren ${config.departure} ${config.originName} - ${config.destinationName}`
        : `Plazas ${config.originName} - ${config.destinationName}`,
      message: `${config.passengers} viajero(s), ${displayDate(config.date)}:\n${lines.join("\n")}\nComprueba la disponibilidad en Renfe antes de comprar.`,
      priority: 4,
      tags: ["train"],
      click: SEARCH_HOME,
    }),
    signal: AbortSignal.timeout(15_000),
  });
  if (!response.ok) {
    throw new Error(`ntfy ha respondido con HTTP ${response.status}.`);
  }
}

async function processResult(trains, config) {
  const available = offeredTrains(trains, config);
  const state = readState(config);
  const previous = new Set(state.available);
  const newlyAvailable = available.filter((train) => !previous.has(trainKey(train)));
  console.log(
    `${new Date().toISOString()} Plazas: ${available.length}; nuevas: ${newlyAvailable.length}.`
  );
  if (newlyAvailable.length && !config.dryRun) await publish(newlyAvailable, config);
  if (newlyAvailable.length && config.dryRun) {
    console.log(JSON.stringify(newlyAvailable, null, 2));
  }
  if (!config.dryRun) {
    writeState({ scope: state.scope, available: available.map(trainKey) });
  }
  return available;
}

async function main() {
  const config = readConfig();
  let browser = null;
  let page = null;
  try {
    while (true) {
      if (config.date < localDate()) {
        console.log("La fecha del viaje ha terminado. Se detiene la vigilancia.");
        break;
      }
      const started = Date.now();
      try {
        if (!browser) {
          browser = await chromium.launch({
            headless: true,
            executablePath: config.chromePath,
          });
        }
        if (!page || page.isClosed()) page = await openSearch(browser, config);
        const trains = await refreshTrains(page, config);
        await processResult(trains, config);
      } catch (error) {
        console.error(`${new Date().toISOString()} ${error.message}`);
        if (page) await page.close().catch(() => {});
        page = null;
        if (config.once) throw error;
      }
      if (config.once) break;
      await sleep(Math.max(0, POLL_INTERVAL_MS - (Date.now() - started)));
    }
  } finally {
    if (browser) await browser.close();
  }
}

if (require.main === module) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}

module.exports = { offeredTrains, openSearch, processResult, readConfig, refreshTrains, trainKey };
