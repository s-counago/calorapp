const fs = require("fs");
const { chromium } = require("playwright-core");

const chromePath = [
  process.env.CHROME_PATH,
  "C:/Program Files/Google/Chrome/Application/chrome.exe",
  "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  "/usr/bin/google-chrome",
  "/usr/bin/chromium",
].find((candidate) => candidate && fs.existsSync(candidate));

if (!chromePath) {
  throw new Error(
    "No se encontró Chrome. Define CHROME_PATH con la ruta del ejecutable."
  );
}

const enginePath =
  "app/src/main/java/com/sejio/calorapp/RenfeAutomationEngine.java";
const source = fs.readFileSync(enginePath, "utf8");
const start = source.indexOf("private static final String PROTOCOL_SCRIPT");
const section = source.slice(start);
const literals = section.match(/"(?:\\.|[^"\\])*"/g) || [];
const protocol = literals.map((literal) => JSON.parse(literal)).join("");

function testPage(partnerAvailable) {
  return `<!doctype html>
    <form id="formVerCoches">
      <input id="maxPlazas" value="1">
      <input name="numeroVagon_0" value="0" cdgoCoche="00001" checked>
      <div id="coche_0" cdgoCoche="00001" style="visibility:visible">
        <input id="plaza_01A" tp="01A" ti="V" ez="0" rrp="f">
        <label for="plaza_01A" style="position:absolute;left:10px;top:10px;width:20px;height:20px"></label>
        <input id="plaza_01B" tp="01B" ti="P" ez="${
          partnerAvailable ? "0" : "2"
        }" rrp="f">
        <label for="plaza_01B" style="position:absolute;left:10px;top:40px;width:20px;height:20px"></label>
      </div>
      <div id="divclasecoche_0_0" cdgoclase="T" cdgogrupo="1"></div>
    </form>
    <script>
      var arrayPlazasSel = [[], []];
      var maxPlazas = [1, 0];
      var enReserva = false;
      var enCambioCoche = false;
      function datosCochePlaza(seat) {
        return [seat.parentNode.getAttribute("cdgoCoche"), "1", "T", "Turista", "1"];
      }
      function reservarPlazas() {
        window.reservedSeat = arrayPlazasSel[0][0].getAttribute("tp");
      }
      document.querySelectorAll("input[tp]").forEach((seat) => {
        seat.addEventListener("change", function () {
          if (this.getAttribute("ez") !== "0") return;
          this.setAttribute("ez", "1");
          arrayPlazasSel[0] = [this];
        });
      });
    </script>`;
}

function seatMapPage(seats, extraMapHtml = "") {
  const seatHtml = seats
    .map(
      ({ name, type = "P", x, y, forward = true }) => `
        <input id="plaza_${name}" tp="${name}" ti="${type}" ez="0" rrp="${
          forward ? "f" : "d"
        }">
        <label for="plaza_${name}" style="position:absolute;left:${x}px;top:${y}px;width:20px;height:20px"></label>`
    )
    .join("");

  return `<!doctype html>
    <form id="formVerCoches">
      <input name="numeroVagon_0" value="0" cdgoCoche="00001" checked>
      <div id="coche_0" cdgoCoche="00001" style="visibility:visible">
        ${seatHtml}
        ${extraMapHtml}
      </div>
    </form>
    <script>
      var arrayPlazasSel = [[], []];
      var maxPlazas = [1, 0];
      var enReserva = false;
      var enCambioCoche = false;
      function datosCochePlaza(seat) {
        return [seat.parentNode.getAttribute("cdgoCoche"), "1", "T", "Turista", "1"];
      }
      function reservarPlazas() {
        window.reservedSeat = arrayPlazasSel[0][0].getAttribute("tp");
      }
      document.querySelectorAll("input[tp]").forEach((seat) => {
        seat.addEventListener("change", function () {
          if (this.getAttribute("ez") !== "0") return;
          this.setAttribute("ez", "1");
          arrayPlazasSel[0] = [this];
        });
      });
    </script>`;
}

function coachPriorityPage() {
  return `<!doctype html>
    <form id="formVerCoches">
      <input name="numeroVagon_0" cdgoCoche="00001" checked>
      <input name="numeroVagon_1" cdgoCoche="00002">
      <div id="coche_0" cdgoCoche="00001" style="visibility:visible">
        <input tp="1" ti="P" ez="0" rrp="d"><label></label>
      </div>
      <div id="coche_1" cdgoCoche="00002" style="visibility:hidden">
        <input tp="3" ti="V" ez="0" rrp="f"><label></label>
      </div>
    </form>
    <script>
      var arrayPlazasSel=[[],[]], maxPlazas=[1,0], enReserva=false, enCambioCoche=false;
      function datosCochePlaza(seat){return [seat.parentNode.getAttribute('cdgoCoche'),'1','T','Turista','1'];}
      function reservarPlazas(){window.reservedCoach=arrayPlazasSel[0][0].parentNode.getAttribute('cdgoCoche');}
      document.querySelectorAll('[name^=numeroVagon_]').forEach(r=>r.addEventListener('change',function(){
        document.querySelectorAll('[name^=numeroVagon_]').forEach(other=>other.checked=other===this);
        document.querySelectorAll('[id^=coche_]').forEach(car=>car.style.visibility=car.getAttribute('cdgoCoche')===this.getAttribute('cdgoCoche')?'visible':'hidden');
      }));
      document.querySelectorAll('input[tp]').forEach(seat=>seat.addEventListener('change',function(){this.setAttribute('ez','1');arrayPlazasSel[0]=[this];}));
    </script>`;
}

function journeyPage() {
  return `<!doctype html>
    <form id="formBean" method="post" action="/vol/trainFormalization.do">
      <select id="originJourneySelect"><option value="31400">Santiago</option><option value="31412">A Coruna</option></select>
      <select id="destinJourneySelect"><option value="31412">A Coruna</option><option value="31400">Santiago</option></select>
      <input name="featuresDataPassesCard.originStation.cdgoEstacion">
      <input name="featuresDataPassesCard.originStation.descEstacion">
      <input name="featuresDataPassesCard.destinStation.cdgoEstacion">
      <input name="featuresDataPassesCard.destinStation.descEstacion">
      <input type="radio" id="formalizationOne" name="mode">
      <input type="radio" id="formalizationMulti" name="mode">
      <input type="checkbox" name="holderes[0].selected">
      <input id="fecha1" name="fecha1"><input id="fecha2" name="fecha2">
      <input id="datesFormalization" name="datesFormalization">
    </form>`;
}

function passesPage(ranges) {
  const cards = ranges
    .map(
      ({ id, start, end }) => `<article class="pass-card">
        <h2>Abono Unico</h2>
        ${start ? `<p>${start === "expiry-only" ? `Fecha de caducidad del abono ${end}` : `${id === "EXPIRING" ? "Comprado el 01/05/2026. " : ""}Valido desde ${start} hasta ${end}`}</p>` : ""}
        <button onclick="submitNew('NEW&amp;${id}')">Nueva formalizacion</button>
      </article>`
    )
    .join("");
  return `<!doctype html>
    ${cards}
    <script>
      function submitNew(value) { window.openedPass = value; }
    </script>`;
}

async function runStep(page, desired) {
  return page.evaluate(
    ({ desired, protocol }) =>
      new Function(`const desired=${JSON.stringify(desired)};${protocol}`)(),
    { desired, protocol }
  );
}

async function settleMap(page, desired) {
  await runStep(page, desired);
  await page.waitForTimeout(1250);
  return JSON.parse(await runStep(page, desired));
}

let browser;

(async () => {
  browser = await chromium.launch({
    headless: true,
    executablePath: chromePath,
  });
  const page = await browser.newPage();
  await page.route("https://venta.renfe.com/vol/selectSeatFormalization.do", (route) =>
    route.fulfill({ contentType: "text/html", body: testPage(true) })
  );
  await page.goto("https://venta.renfe.com/vol/selectSeatFormalization.do");

  const leader = await settleMap(page, {
    findAdjacentPartner: true,
    requiredSeat: null,
  });
  if (
    leader.status !== "acted" ||
    leader.partnerSeat.seat !== "01B" ||
    leader.partnerSeat.coach !== "00001"
  ) {
    throw new Error(`Pair leader failed: ${JSON.stringify(leader)}`);
  }
  await page.waitForTimeout(200);
  if ((await page.evaluate(() => window.reservedSeat)) !== "01A") {
    throw new Error("The first seat was not reserved");
  }

  await page.unroute("https://venta.renfe.com/vol/selectSeatFormalization.do");
  await page.route("https://venta.renfe.com/vol/selectSeatFormalization.do", (route) =>
    route.fulfill({ contentType: "text/html", body: testPage(true) })
  );
  await page.goto("https://venta.renfe.com/vol/selectSeatFormalization.do");
  const follower = await settleMap(page, {
    findAdjacentPartner: false,
    requiredSeat: leader.partnerSeat,
  });
  if (follower.status !== "acted") {
    throw new Error(`Pair follower failed: ${JSON.stringify(follower)}`);
  }
  await page.waitForTimeout(200);
  if ((await page.evaluate(() => window.reservedSeat)) !== "01B") {
    throw new Error("The exact partner seat was not reserved");
  }

  await page.unroute("https://venta.renfe.com/vol/selectSeatFormalization.do");
  await page.route("https://venta.renfe.com/vol/selectSeatFormalization.do", (route) =>
    route.fulfill({ contentType: "text/html", body: testPage(false) })
  );
  await page.goto("https://venta.renfe.com/vol/selectSeatFormalization.do");
  const unavailable = await settleMap(page, {
    findAdjacentPartner: false,
    requiredSeat: leader.partnerSeat,
  });
  if (unavailable.status !== "needs_user") {
    throw new Error(`Unavailable-seat guard failed: ${JSON.stringify(unavailable)}`);
  }

  const pairCases = [
    {
      name: "odd numeric boundary",
      seats: [
        { name: "2", type: "V", x: 10, y: 10 },
        { name: "3", type: "P", x: 10, y: 40 },
      ],
      expectedStatus: "needs_user",
    },
    {
      name: "numeric pair with even upper seat",
      seats: [
        { name: "3", type: "V", x: 10, y: 10 },
        { name: "4", type: "P", x: 10, y: 40 },
      ],
      expectedStatus: "acted",
      expectedSeats: ["3", "4"],
    },
    {
      name: "AVE aisle and middle pair",
      seats: [
        { name: "01C", type: "P", x: 10, y: 10 },
        { name: "01D", type: "C", x: 10, y: 40 },
      ],
      expectedStatus: "acted",
      expectedSeats: ["01C", "01D"],
    },
    {
      name: "AVE middle and window pair",
      seats: [
        { name: "01D", type: "C", x: 10, y: 10 },
        { name: "01E", type: "V", x: 10, y: 40 },
      ],
      expectedStatus: "acted",
      expectedSeats: ["01D", "01E"],
    },
  ];

  for (const pairCase of pairCases) {
    await page.unroute("https://venta.renfe.com/vol/selectSeatFormalization.do");
    await page.route("https://venta.renfe.com/vol/selectSeatFormalization.do", (route) =>
      route.fulfill({ contentType: "text/html", body: seatMapPage(pairCase.seats) })
    );
    await page.goto("https://venta.renfe.com/vol/selectSeatFormalization.do");
    const result = await settleMap(page, {
      findAdjacentPartner: true,
      requiredSeat: null,
    });
    if (result.status !== pairCase.expectedStatus) {
      throw new Error(`${pairCase.name} failed: ${JSON.stringify(result)}`);
    }
    if (
      pairCase.expectedSeats &&
      !pairCase.expectedSeats.includes(result.partnerSeat?.seat)
    ) {
      throw new Error(`${pairCase.name} chose the wrong partner: ${JSON.stringify(result)}`);
    }
  }

  const tableMap = seatMapPage(
    [
      { name: "1", type: "V", x: 10, y: 10 },
      { name: "2", type: "P", x: 10, y: 40 },
      { name: "3", type: "V", x: 240, y: 10 },
      { name: "4", type: "P", x: 240, y: 40 },
    ],
    '<img src="mesa.svg" alt="Mesa" style="position:absolute;left:10px;top:75px;width:30px;height:30px">'
  );
  await page.unroute("https://venta.renfe.com/vol/selectSeatFormalization.do");
  await page.route("https://venta.renfe.com/vol/selectSeatFormalization.do", (route) =>
    route.fulfill({ contentType: "text/html", body: tableMap })
  );
  await page.goto("https://venta.renfe.com/vol/selectSeatFormalization.do");
  const pairAwayFromTable = await settleMap(page, {
    findAdjacentPartner: true,
    requiredSeat: null,
  });
  if (
    pairAwayFromTable.status !== "acted" ||
    pairAwayFromTable.partnerSeat?.seat !== "4"
  ) {
    throw new Error(
      `Pair table penalty failed: ${JSON.stringify(pairAwayFromTable)}`
    );
  }

  await page.goto("https://venta.renfe.com/vol/selectSeatFormalization.do");
  const singleAwayFromTable = await settleMap(page, {
    findAdjacentPartner: false,
    requiredSeat: null,
  });
  await page.waitForTimeout(200);
  if (
    singleAwayFromTable.status !== "acted" ||
    (await page.evaluate(() => window.reservedSeat)) !== "3"
  ) {
    throw new Error(
      `Single-seat table penalty failed: ${JSON.stringify(singleAwayFromTable)}`
    );
  }

  await page.unroute("https://venta.renfe.com/vol/selectSeatFormalization.do");
  await page.route("https://venta.renfe.com/vol/selectSeatFormalization.do", route =>
    route.fulfill({ contentType: "text/html", body: coachPriorityPage() })
  );
  await page.goto("https://venta.renfe.com/vol/selectSeatFormalization.do");
  await runStep(page, { findAdjacentPartner: false, requiredSeat: null });
  await page.waitForTimeout(1250);
  let coachResult;
  for (let attempt = 0; attempt < 4; attempt++) {
    coachResult = JSON.parse(await runStep(page, { findAdjacentPartner: false, requiredSeat: null }));
    if (coachResult.status === "acted") break;
  }
  await page.waitForTimeout(200);
  if (coachResult.status !== "acted" || (await page.evaluate(() => window.reservedCoach)) !== "00001") {
    throw new Error(`Coach priority failed: ${JSON.stringify(coachResult)}`);
  }

  const journeyUrl = "https://venta.renfe.com/vol/journeyFormalization.do";
  let submittedJourney;
  await page.route(journeyUrl, route => route.fulfill({ contentType: "text/html", body: journeyPage() }));
  await page.route("https://venta.renfe.com/vol/trainFormalization.do", route => {
    submittedJourney = route.request().postData() || "";
    return route.fulfill({ contentType: "text/html", body: "trains" });
  });
  await page.goto(journeyUrl);
  const fridayDesired = {
    dates: ["21/08/2026"], departure: "07:12", arrival: "07:43", trainType: "AVANT",
    originCode: "31400", originName: "SANTIAGO DE COMPOSTELA-DANIEL CASTELAO",
    destinationCode: "31412", destinationName: "A CORUÑA"
  };
  const preparedFriday = JSON.parse(await runStep(page, fridayDesired));
  if (preparedFriday.status !== "waiting") {
    throw new Error(`Friday form did not wait for Renfe field handlers: ${JSON.stringify(preparedFriday)}`);
  }
  await page.waitForTimeout(300);
  const fridayResult = JSON.parse(await runStep(page, fridayDesired));
  await page.waitForURL("**/trainFormalization.do");
  if (fridayResult.status !== "acted" || !submittedJourney.includes("datesFormalization=21%2F08%2F2026") || !submittedJourney.includes("fecha1=21%2F08%2F2026")) {
    throw new Error(`Friday 07:12 date submission failed: ${fridayResult.message}; ${submittedJourney}`);
  }

  const passesUrl = "https://venta.renfe.com/vol/myPassesCard.do";
  await page.unroute("https://venta.renfe.com/vol/selectSeatFormalization.do");
  await page.route(passesUrl, (route) =>
    route.fulfill({
      contentType: "text/html",
      body: passesPage([
        { id: "OLD", start: "01/01/2026", end: "30/06/2026" },
        { id: "NEW", start: "01/07/2026", end: "31/12/2026" },
      ]),
    })
  );
  await page.goto(passesUrl);
  const selectedPass = JSON.parse(await runStep(page, { dates: ["10/08/2026"] }));
  if (
    selectedPass.status !== "acted" ||
    (await page.evaluate(() => window.openedPass)) !== "NEW&NEW"
  ) {
    throw new Error(`Date-based pass selection failed: ${JSON.stringify(selectedPass)}`);
  }

  await page.unroute(passesUrl);
  await page.route(passesUrl, (route) =>
    route.fulfill({
      contentType: "text/html",
      body: passesPage([
        { id: "OLD", start: "01/01/2026", end: "30/06/2026" },
        { id: "EXPIRING", start: "19/04/2026", end: "18/08/2026" },
      ]),
    })
  );
  await page.goto(passesUrl);
  const expiringPass = JSON.parse(await runStep(page, { dates: ["17/08/2026"] }));
  if (
    expiringPass.status !== "acted" ||
    (await page.evaluate(() => window.openedPass)) !== "NEW&EXPIRING"
  ) {
    throw new Error(`Expiring-pass boundary failed: ${JSON.stringify(expiringPass)}`);
  }

  await page.unroute(passesUrl);
  await page.route(passesUrl, (route) =>
    route.fulfill({
      contentType: "text/html",
      body: passesPage([
        { id: "LATER", start: "expiry-only", end: "17/09/2026" },
        { id: "SOONER", start: "expiry-only", end: "18/08/2026" },
      ]),
    })
  );
  await page.goto(passesUrl);
  const expiryOnlyPass = JSON.parse(await runStep(page, { dates: ["17/08/2026"] }));
  if (
    expiryOnlyPass.status !== "acted" ||
    (await page.evaluate(() => window.openedPass)) !== "NEW&SOONER"
  ) {
    throw new Error(`Expiry-only pass selection failed: ${JSON.stringify(expiryOnlyPass)}`);
  }

  await page.unroute(passesUrl);
  await page.route(passesUrl, (route) =>
    route.fulfill({
      contentType: "text/html",
      body: passesPage([
        { id: "OLD", start: "01/01/2026", end: "31/12/2026" },
        { id: "NEW", start: "01/07/2026", end: "30/06/2027" },
      ]),
    })
  );
  await page.goto(passesUrl);
  const ambiguousPass = JSON.parse(await runStep(page, { dates: ["10/08/2026"] }));
  if (ambiguousPass.status !== "needs_user") {
    throw new Error(`Ambiguous-pass guard failed: ${JSON.stringify(ambiguousPass)}`);
  }

  await page.unroute(passesUrl);
  await page.route(passesUrl, (route) =>
    route.fulfill({
      contentType: "text/html",
      body: passesPage([
        { id: "OLD", start: "01/01/2026", end: "30/06/2026" },
        { id: "NEW", start: "01/07/2026", end: "31/12/2026" },
      ]),
    })
  );
  await page.goto(passesUrl);
  const missingPass = JSON.parse(await runStep(page, { dates: ["10/08/2027"] }));
  if (missingPass.status !== "needs_user") {
    throw new Error(`Missing-pass guard failed: ${JSON.stringify(missingPass)}`);
  }

  await page.unroute(passesUrl);
  await page.route(passesUrl, (route) =>
    route.fulfill({
      contentType: "text/html",
      body: passesPage([{ id: "ONLY" }]),
    })
  );
  await page.goto(passesUrl);
  const onlyPass = JSON.parse(await runStep(page, { dates: ["10/08/2026"] }));
  if (
    onlyPass.status !== "acted" ||
    (await page.evaluate(() => window.openedPass)) !== "NEW&ONLY"
  ) {
    throw new Error(`Single-pass fallback failed: ${JSON.stringify(onlyPass)}`);
  }

  await browser.close();
  console.log("Seat and pass-selection smoke tests passed");
})().catch((error) => {
  console.error(error);
  process.exitCode = 1;
  if (browser) {
    browser.close().catch(() => {});
  }
});
