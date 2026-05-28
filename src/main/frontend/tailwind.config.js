/** @type {import('tailwindcss').Config} */
module.exports = {
  darkMode: 'class',
  presets: [require('@spartan-ng/brain/hlm-tailwind-preset')],
  content: ['./src/**/*.{html,ts}'],
  theme: { extend: {} },
  plugins: [],
};
