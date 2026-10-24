// A static ribbon field supplies brand texture without timers, requests or background animation.
(() => {
  'use strict';
  const canvas = document.getElementById('field');
  const context = canvas?.getContext('2d');
  if (!context) return;
  function draw() {
    context.clearRect(0, 0, 560, 480);
    context.save();
    context.translate(280, 240);
    context.rotate(-Math.PI / 6);
    for (let line = -4; line <= 4; line++) {
      const gradient = context.createLinearGradient(-280, 0, 280, 0);
      gradient.addColorStop(0, 'rgba(95,183,198,0)');
      gradient.addColorStop(.5, `rgba(112,173,199,${.12 - Math.abs(line) * .016})`);
      gradient.addColorStop(1, 'rgba(151,138,209,0)');
      context.fillStyle = gradient;
      context.fillRect(-320, line * 43, 640, 18);
    }
    context.restore();
  }
  draw();
})();
